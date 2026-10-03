/**
 * Stronghold Protocol (卫戍协议：盟约) Cloudflare Workers 反向代理与加速脚本
 * 
 * 功能：
 * 1. 目标源站反向代理：默认转发至 https://game.starst.site
 * 2. WebSocket 实时透传：支持 /ws 路径的双向长连接
 * 3. 静态资产边缘加速：自动对 Spine 骨骼、音频、贴图等进行 24h CDN 缓存
 * 4. 跨域与请求头重写：修正 Host 并透传真实客户端 IP (CF-Connecting-IP)
 */

const UPSTREAM = 'game.starst.site';

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

    // 3. 处理 WebSocket 联机握手 (/ws)
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
        body: request.body,
        redirect: 'follow',
        cf: cfOptions,
      });

      // 5. 复制响应头并增加 CORS 支持
      const resHeaders = new Headers(response.headers);
      resHeaders.set('Access-Control-Allow-Origin', '*');

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
