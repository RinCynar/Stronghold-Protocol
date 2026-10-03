/**
 * Stronghold Protocol (卫戍协议：盟约) Cloudflare Workers 反向代理与加速脚本
 *
 * 功能：
 * 1. 目标源站反向代理：转发至 Rin 自己的 Render 部署
 *    (stronghold-protocol-zmmq.onrender.com)
 * 2. WebSocket 实时透传：支持 /ws 路径的双向长连接
 * 3. 静态资产边缘加速：Spine 骨骼、音频、贴图等 24h CDN 缓存
 * 4. 请求头重写：修正 Host，透传真实客户端 IP (CF-Connecting-IP)
 * 5. 移动端 CSS 实时补丁：修复手机横屏下结算界面被截断
 */

const UPSTREAM = 'stronghold-protocol-zmmq.onrender.com';

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // 1. 目标地址重写为源站
    const targetUrl = new URL(request.url);
    targetUrl.hostname = UPSTREAM;
    targetUrl.protocol = 'https:';
    targetUrl.port = '443';

    // 2. 复制并重写请求头
    const reqHeaders = new Headers(request.headers);
    reqHeaders.set('Host', UPSTREAM);
    reqHeaders.set('Referer', `https://${UPSTREAM}/`);

    // 3. 处理 WebSocket 联机握手 (/ws)：直接透传 101 升级响应
    const upgradeHeader = request.headers.get('Upgrade');
    if (upgradeHeader && upgradeHeader.toLowerCase() === 'websocket') {
      return fetch(targetUrl.toString(), {
        method: request.method,
        headers: reqHeaders,
      });
    }

    // 4. 静态资源边缘 CDN 缓存（Spine 模型、音频、纹理、字体等）
    const isAsset = /\.(png|jpe?g|webp|skel|atlas|mp3|ogg|wav|wasm|ttf|woff2)$/i.test(url.pathname);
    const cfOptions = isAsset
      ? {
          cacheEverything: true,
          cacheTtl: 86400, // 静态资源边缘缓存 1 天
        }
      : {};

    try {
      const response = await fetch(targetUrl.toString(), {
        method: request.method,
        headers: reqHeaders,
        body: (request.method === 'GET' || request.method === 'HEAD') ? undefined : request.body,
        redirect: 'follow',
        cf: cfOptions,
      });

      // 5. 响应头透传与 CORS 支持
      const resHeaders = new Headers(response.headers);
      resHeaders.set('Access-Control-Allow-Origin', '*');

      // 6. 移动端 CSS 实时补丁（手机横屏结算界面被截断的修复）
      const contentType = response.headers.get('content-type') || '';
      if (response.status === 200 && contentType.includes('css')) {
        if (url.pathname === '/css/theme.css') {
          let css = await response.text();
          css = css.replaceAll('clamp(40px,', 'clamp(16px,');
          // 文本被改写过，清理可能导致缓存/解压异常的标头
          resHeaders.delete('content-encoding');
          resHeaders.delete('content-length');
          resHeaders.delete('etag');
          return new Response(css, { status: 200, headers: resHeaders });
        }
        if (url.pathname === '/css/screens/result.css') {
          let css = await response.text();
          css = css.replace(
            '.result__hero { display: flex; flex-direction: column; align-items: flex-start; gap: .16rem; padding-top: .3rem; min-height: 0; }',
            '.result__hero { display: flex; flex-direction: column; align-items: flex-start; gap: clamp(.08rem, 1.2vh, .16rem); padding-top: clamp(.1rem, 2vh, .3rem); min-height: 0; overflow-y: auto; overflow-x: hidden; scrollbar-width: thin; }'
          );
          resHeaders.delete('content-encoding');
          resHeaders.delete('content-length');
          resHeaders.delete('etag');
          return new Response(css, { status: 200, headers: resHeaders });
        }
      }

      return new Response(response.body, {
        status: response.status,
        statusText: response.statusText,
        headers: resHeaders,
      });
    } catch (err) {
      return new Response(`Proxy Error: ${err.message}`, { status: 502 });
    }
  },
};
