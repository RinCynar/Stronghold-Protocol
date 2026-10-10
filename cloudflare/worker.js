/**
 * Stronghold Protocol (卫戍协议：盟约) - Cloudflare Workers 智能入口与调度主页
 *
 * 核心功能：
 * 1. 作为 ak.rincynar.top 官方主页与调度中继站，秒级响应，永不宕机；
 * 2. 网页端仅提供一个高质感明日方舟风格“启动协议 / 接入游戏”按钮；
 * 3. 点击后（及后台预热）在玩家浏览器端毫秒级并发探测各节点往返延迟：
 *    - 第一梯队（优先使用）：12 个多线路分流加速节点（dpdns.org 及 rincynar.top 分流），秒级匹配最低延迟线路；
 *    - 第二梯队（第一梯队均不可用时）：主节点 https://arkimg.dpdns.org；
 *    - 第三梯队（第二梯队亦不可用时）：终极兜底节点 https://stronghold-protocol.rincynar.top；
 *    - 全挂时显示网络诊断与重试提示；
 * 4. 保留 URL 上的 Query 参数（如同盟房间号 ?room=XXXX）并无缝透传至目标节点；
 * 5. 提供推荐的 Android 客户端 (v1.0.11) 最新版本下载通道。
 */

const TIER_1_NODES = [
  'https://1.arkimg.dpdns.org',
  'https://2.arkimg.dpdns.org',
  'https://3.arkimg.dpdns.org',
  'https://4.arkimg.dpdns.org',
  'https://1.agmua.dpdns.org',
  'https://2.agmua.dpdns.org',
  'https://3.agmua.dpdns.org',
  'https://4.agmua.dpdns.org',
  'https://ak.1.rincynar.top',
  'https://ak.2.rincynar.top',
  'https://ak.3.rincynar.top',
  'https://ak.4.rincynar.top'
];

const TIER_2_NODES = [
  'https://arkimg.dpdns.org'
];

const TIER_3_NODES = [
  'https://stronghold-protocol.rincynar.top'
];

const FAVICON_SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><rect width="24" height="24" fill="#0c0f0e"/><path fill="#4ed8af" d="M5 3h3v2h2V3h4v2h2V3h3v5l-2 2v7l2 2v2H5v-2l2-2v-7L5 8z"/></svg>`;

const PORTAL_HTML = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover" />
  <title>卫戍协议：盟约 · STRONGHOLD PROTOCOL | 调度终端</title>
  <link rel="icon" href="data:image/svg+xml,${encodeURIComponent(FAVICON_SVG)}" />
  <meta name="theme-color" content="#0c0f0e" />
  <meta name="description" content="明日方舟「卫戍协议：盟约」非官方同人联机网页复刻 - 智能线路调度中心" />
  <style>
    :root {
      --bg: #0c0f0e;
      --card-bg: rgba(18, 24, 21, 0.85);
      --card-border: rgba(78, 216, 175, 0.22);
      --mint: #4ed8af;
      --mint-hi: #17f9b7;
      --mint-glow: rgba(23, 249, 183, 0.35);
      --amber: #f5a623;
      --red: #ff4d4f;
      --text-hi: #f3f7f5;
      --text-md: #a0b6ad;
      --text-lo: #536b61;
      --font-main: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans SC", sans-serif;
      --font-code: "JetBrains Mono", Consolas, Menlo, monospace;
    }

    * { box-sizing: border-box; margin: 0; padding: 0; }

    body {
      background-color: var(--bg);
      color: var(--text-hi);
      font-family: var(--font-main);
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 20px;
      user-select: none;
      position: relative;
      overflow-x: hidden;
      background-image: 
        radial-gradient(circle at 50% 30%, rgba(23, 249, 183, 0.08) 0%, transparent 60%),
        linear-gradient(rgba(78, 216, 175, 0.03) 1px, transparent 1px),
        linear-gradient(90deg, rgba(78, 216, 175, 0.03) 1px, transparent 1px);
      background-size: 100% 100%, 36px 36px, 36px 36px;
    }

    /* 顶部装饰条 */
    .header-bar {
      position: absolute;
      top: 0;
      left: 0;
      right: 0;
      height: 3px;
      background: linear-gradient(90deg, transparent, var(--mint), transparent);
    }

    /* 主卡片容器 */
    .portal-card {
      width: 100%;
      max-width: 580px;
      background: var(--card-bg);
      border: 1px solid var(--card-border);
      border-radius: 4px;
      padding: 36px 32px 30px;
      box-shadow: 0 16px 48px rgba(0, 0, 0, 0.75), 0 0 24px rgba(23, 249, 183, 0.08);
      position: relative;
      backdrop-filter: blur(12px);
      -webkit-backdrop-filter: blur(12px);
      text-align: center;
      animation: fadeIn 0.4s ease-out;
    }

    /* 四角战术边框 */
    .corner {
      position: absolute;
      width: 10px;
      height: 10px;
      border-color: var(--mint);
      pointer-events: none;
    }
    .corner.tl { top: -1px; left: -1px; border-top: 2px solid; border-left: 2px solid; }
    .corner.tr { top: -1px; right: -1px; border-top: 2px solid; border-right: 2px solid; }
    .corner.bl { bottom: -1px; left: -1px; border-bottom: 2px solid; border-left: 2px solid; }
    .corner.br { bottom: -1px; right: -1px; border-bottom: 2px solid; border-right: 2px solid; }

    /* 战术副标 */
    .sub-badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-size: 11px;
      letter-spacing: 2px;
      color: var(--mint);
      border: 1px solid rgba(78, 216, 175, 0.35);
      background: rgba(78, 216, 175, 0.08);
      padding: 3px 12px;
      border-radius: 2px;
      margin-bottom: 18px;
      text-transform: uppercase;
    }
    .pulse-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--mint-hi);
      box-shadow: 0 0 8px var(--mint-hi);
      animation: pulse 1.8s infinite;
    }

    /* 主标题 */
    h1.title {
      font-size: 32px;
      font-weight: 900;
      letter-spacing: 4px;
      color: var(--text-hi);
      margin-bottom: 4px;
      text-shadow: 0 2px 12px rgba(0, 0, 0, 0.6);
    }
    .subtitle {
      font-size: 13px;
      letter-spacing: 3px;
      color: var(--text-md);
      font-weight: 500;
      margin-bottom: 24px;
      text-transform: uppercase;
    }

    /* 描述简评 */
    .desc {
      font-size: 14px;
      line-height: 1.6;
      color: var(--text-md);
      margin-bottom: 30px;
    }

    /* 核心启动大按钮 */
    .btn-container {
      margin-bottom: 24px;
      position: relative;
    }
    .launch-btn {
      width: 100%;
      height: 64px;
      background: linear-gradient(180deg, #182621 0%, #111a16 100%);
      border: 1px solid var(--mint);
      border-radius: 3px;
      color: var(--mint-hi);
      font-family: var(--font-main);
      cursor: pointer;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      position: relative;
      overflow: hidden;
      box-shadow: 0 4px 20px rgba(0, 0, 0, 0.5), inset 0 0 16px rgba(23, 249, 183, 0.12);
      transition: all 0.2s cubic-bezier(0.25, 1, 0.5, 1);
    }
    .launch-btn:hover:not(:disabled) {
      background: linear-gradient(180deg, #20352e 0%, #16241f 100%);
      box-shadow: 0 6px 26px var(--mint-glow), inset 0 0 24px rgba(23, 249, 183, 0.25);
      border-color: var(--mint-hi);
      transform: translateY(-1px);
    }
    .launch-btn:active:not(:disabled) {
      transform: translateY(1px);
      box-shadow: 0 2px 10px rgba(23, 249, 183, 0.2);
    }
    .launch-btn:disabled {
      cursor: wait;
      opacity: 0.9;
    }

    .btn-main-text {
      font-size: 20px;
      font-weight: 800;
      letter-spacing: 3px;
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .btn-sub-text {
      font-size: 11px;
      letter-spacing: 2px;
      color: var(--text-lo);
      margin-top: 3px;
      font-family: var(--font-code);
    }

    /* 按钮扫描动效条 */
    .btn-scan {
      position: absolute;
      top: 0;
      left: -100%;
      width: 100%;
      height: 100%;
      background: linear-gradient(90deg, transparent, rgba(23, 249, 183, 0.25), transparent);
      pointer-events: none;
      transition: none;
    }
    .launch-btn.is-probing .btn-scan {
      animation: scan 1.2s infinite linear;
    }

    /* 终端控制台输出框 */
    .console-box {
      background: rgba(5, 8, 7, 0.85);
      border: 1px solid rgba(78, 216, 175, 0.15);
      border-radius: 3px;
      padding: 12px 16px;
      text-align: left;
      font-family: var(--font-code);
      font-size: 12px;
      line-height: 1.5;
      color: var(--mint);
      margin-bottom: 24px;
      min-height: 48px;
      display: flex;
      align-items: center;
    }
    .console-prefix {
      color: var(--text-lo);
      margin-right: 8px;
      flex-shrink: 0;
    }
    .console-text {
      word-break: break-all;
    }

    /* 手机客户端推荐推荐位 */
    .app-badge-link {
      display: flex;
      align-items: center;
      justify-content: space-between;
      background: rgba(23, 249, 183, 0.05);
      border: 1px solid rgba(78, 216, 175, 0.2);
      border-radius: 3px;
      padding: 12px 18px;
      text-decoration: none;
      color: var(--text-hi);
      transition: all 0.2s ease;
      margin-top: 8px;
    }
    .app-badge-link:hover {
      background: rgba(23, 249, 183, 0.1);
      border-color: var(--mint);
    }
    .app-left {
      display: flex;
      align-items: center;
      gap: 12px;
      text-align: left;
    }
    .app-icon {
      font-size: 24px;
      line-height: 1;
    }
    .app-title {
      font-size: 13px;
      font-weight: 700;
      color: var(--mint-hi);
      display: block;
      margin-bottom: 2px;
    }
    .app-sub {
      font-size: 11px;
      color: var(--text-md);
      display: block;
    }
    .app-arrow {
      color: var(--mint);
      font-weight: 700;
      font-size: 16px;
    }

    /* 页脚 */
    .footer {
      margin-top: 24px;
      font-size: 12px;
      color: var(--text-lo);
      line-height: 1.6;
    }
    .footer a {
      color: var(--text-md);
      text-decoration: none;
      border-bottom: 1px dashed var(--text-lo);
    }
    .footer a:hover {
      color: var(--mint);
      border-color: var(--mint);
    }

    @keyframes pulse {
      0%, 100% { opacity: 1; transform: scale(1); }
      50% { opacity: 0.4; transform: scale(0.85); }
    }
    @keyframes scan {
      0% { left: -100%; }
      100% { left: 100%; }
    }
    @keyframes fadeIn {
      from { opacity: 0; transform: translateY(8px); }
      to { opacity: 1; transform: translateY(0); }
    }

    @media (max-width: 480px) {
      .portal-card { padding: 28px 20px 22px; }
      h1.title { font-size: 26px; }
      .launch-btn { height: 58px; }
      .btn-main-text { font-size: 17px; }
    }
  </style>
</head>
<body>
  <div class="header-bar"></div>

  <main class="portal-card">
    <div class="corner tl"></div>
    <div class="corner tr"></div>
    <div class="corner bl"></div>
    <div class="corner br"></div>

    <div class="sub-badge">
      <span class="pulse-dot"></span>
      <span>PRTS RELAY · 智能网络调度中心</span>
    </div>

    <h1 class="title">卫戍协议：盟约</h1>
    <div class="subtitle">STRONGHOLD PROTOCOL · ALLIANCE</div>

    <p class="desc">
      《明日方舟》同人联机自走棋网页复刻版。<br/>
      系统将实时分析您的网络环境，并自动为您接入延迟最低、连接最稳定的服务器节点。
    </p>

    <div class="btn-container">
      <button id="launch-btn" class="launch-btn" type="button">
        <div class="btn-scan"></div>
        <div class="btn-main-text">
          <span>接入神经联结 · 启动协议</span>
          <span>➔</span>
        </div>
        <div class="btn-sub-text">START PROTOCOL · AUTO SELECT ROUTE</div>
      </button>
    </div>

    <div class="console-box">
      <span class="console-prefix">[PRTS]</span>
      <span class="console-text" id="console-msg">系统就绪。点击上方按钮自动测速并接入可用服务器节点。</span>
    </div>

    <a href="https://github.com/RinCynar/Stronghold-Protocol/releases/latest" target="_blank" class="app-badge-link" rel="noreferrer">
      <div class="app-left">
        <span class="app-icon">📱</span>
        <div>
          <span class="app-title">手机玩家强烈推荐：Android 客户端 (v1.0.11)</span>
          <span class="app-sub">独立浏览器内核 · 原生全屏横屏免遮挡 · 后台防掉线保活</span>
        </div>
      </div>
      <span class="app-arrow">➔</span>
    </a>

    <footer class="footer">
      本作为玩家自制非官方同人作品，仅供学习交流，严禁任何商业盈利。<br/>
      <a href="https://github.com/RinCynar/Stronghold-Protocol" target="_blank" rel="noreferrer">GitHub 开源仓库</a> · 罗德岛卫戍协议战术终端
    </footer>
  </main>

  <script>
    (function() {
      const TIER_1 = ${JSON.stringify(TIER_1_NODES)};
      const TIER_2 = ${JSON.stringify(TIER_2_NODES)};
      const TIER_3 = ${JSON.stringify(TIER_3_NODES)};

      const launchBtn = document.getElementById('launch-btn');
      const consoleMsg = document.getElementById('console-msg');
      let isConnecting = false;
      let cachedFastest = null;

      // 日志输出
      function log(text, color) {
        if (consoleMsg) {
          consoleMsg.textContent = text;
          if (color) consoleMsg.style.color = color;
        }
      }

      // 单节点连通与延迟探测 (采用不受同源策略限制的 CSS 加载机制，4xx/5xx/GFW RST 均会精准触发 onerror)
      function probeNode(url, timeoutMs = 2500) {
        return new Promise((resolve) => {
          const start = performance.now();
          const link = document.createElement('link');
          link.rel = 'stylesheet';
          link.href = url + '/css/theme.css?_t=' + Date.now();
          let settled = false;

          const timer = setTimeout(() => {
            if (settled) return;
            settled = true;
            try { link.remove(); } catch (e) {}
            resolve({ url, ok: false, rtt: Infinity, err: 'TIMEOUT' });
          }, timeoutMs);

          link.onload = () => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            const rtt = Math.max(1, Math.round(performance.now() - start));
            try { link.remove(); } catch (e) {}
            resolve({ url, ok: true, rtt });
          };

          link.onerror = () => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            try { link.remove(); } catch (e) {}
            resolve({ url, ok: false, rtt: Infinity, err: 'ERROR' });
          };

          document.head.appendChild(link);
        });
      }

      // Promise.any 兼容实现
      function anyPromise(promises) {
        return new Promise((resolve, reject) => {
          let rejectedCount = 0;
          if (!promises || promises.length === 0) return reject(new Error('Empty'));
          promises.forEach((p) => {
            Promise.resolve(p).then((res) => {
              if (res && res.ok) resolve(res);
              else {
                rejectedCount++;
                if (rejectedCount === promises.length) reject(new Error('All failed'));
              }
            }).catch(() => {
              rejectedCount++;
              if (rejectedCount === promises.length) reject(new Error('All failed'));
            });
          });
        });
      }

      // 梯队探测逻辑
      async function detectBestNode() {
        // 若预热已探测到可用节点，直接采用
        if (cachedFastest && cachedFastest.ok) {
          return cachedFastest;
        }

        // 1. 优先探测第一梯队（12 个分流节点并发测速，率先响应且健康的节点胜出）
        log('正在探测第一梯队分流节点网络连通性...');
        try {
          const fastest = await anyPromise(TIER_1.map((u) => probeNode(u, 2600)));
          if (fastest && fastest.ok) {
            return fastest;
          }
        } catch (e) {}

        // 2. 第一梯队均不可用时，尝试第二梯队主节点
        log('分流节点繁忙，正在探测核心备用节点...', 'var(--amber)');
        for (const u of TIER_2) {
          const res = await probeNode(u, 3000);
          if (res.ok) return res;
        }

        // 3. 第二梯队亦不可用时，尝试第三梯队兜底节点
        log('正在接入终极兜底节点...', 'var(--amber)');
        for (const u of TIER_3) {
          const res = await probeNode(u, 3500);
          if (res.ok) return res;
        }

        return null;
      }

      // Failover endpoints handed to the game page (net.js opt-in multi-node failover): every other
      // node as wss://…/ws, tier order preserved. Carried in the URL fragment so it never reaches
      // the game server's logs; the page parses it and strips the fragment (main.js).
      function fallbackList(excludeUrl) {
        const norm = (u) => String(u).replace(/\/+$/, '');
        const exclude = norm(excludeUrl);
        const seen = new Set();
        const out = [];
        for (const u of [...TIER_1, ...TIER_2, ...TIER_3]) {
          const host = norm(u);
          if (host === exclude || seen.has(host)) continue;
          seen.add(host);
          out.push(host.replace(/^http/i, 'ws') + '/ws');
        }
        return out;
      }

      // 执行跳转
      function redirectToNode(targetUrl, rtt) {
        log('最优线路已匹配，正在建立联结进入战场...', 'var(--mint-hi)');
        launchBtn.classList.remove('is-probing');
        launchBtn.style.borderColor = 'var(--mint-hi)';
        launchBtn.querySelector('.btn-main-text span').textContent = '✔ 正在进入战场...';

        const search = window.location.search || '';
        // The fragment carries the failover list (deep links keep ?room= in the search; a fragment
        // on the portal URL itself is meaningless and is replaced).
        const fb = fallbackList(targetUrl);
        const dest = targetUrl.replace(/\/+$/, '') + '/' + search + (fb.length ? '#spfb=' + encodeURIComponent(fb.join(',')) : '');

        setTimeout(() => {
          window.location.href = dest;
        }, 300);
      }

      // 按钮点击处理
      async function onLaunchClick() {
        if (isConnecting) return;
        isConnecting = true;
        launchBtn.disabled = true;
        launchBtn.classList.add('is-probing');
        launchBtn.querySelector('.btn-main-text span').textContent = '正在优选接入线路...';

        try {
          const best = await detectBestNode();
          if (best && best.ok) {
            redirectToNode(best.url, best.rtt);
          } else {
            throw new Error('All nodes unavailable');
          }
        } catch (err) {
          isConnecting = false;
          launchBtn.disabled = false;
          launchBtn.classList.remove('is-probing');
          launchBtn.style.borderColor = 'var(--red)';
          launchBtn.querySelector('.btn-main-text span').textContent = '线路繁忙，点击重试';
          log('❌ 当前所有线路均无法连通，请检查网络设置或使用 Android 客户端游玩。', 'var(--red)');
        }
      }

      launchBtn.addEventListener('click', onLaunchClick);

      // 后台静默预热：页面加载 600ms 后后台并发测速，玩家阅读界面点击时即可秒进
      setTimeout(() => {
        if (!isConnecting) {
          anyPromise(TIER_1.map((u) => probeNode(u, 2600))).then((res) => {
            if (res && res.ok && !cachedFastest) {
              cachedFastest = res;
            }
          }).catch(() => {});
        }
      }, 600);
    })();
  </script>
</body>
</html>`;

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // 1. Favicon 响应
    if (url.pathname === '/favicon.ico') {
      return new Response(FAVICON_SVG, {
        headers: {
          'Content-Type': 'image/svg+xml',
          'Cache-Control': 'public, max-age=86400',
        },
      });
    }

    // 2. 健康检查
    if (url.pathname === '/healthz') {
      return new Response('OK', {
        headers: { 'Content-Type': 'text/plain' },
      });
    }

    // 3. 返回调度终端主页
    return new Response(PORTAL_HTML, {
      status: 200,
      headers: {
        'Content-Type': 'text/html; charset=utf-8',
        'Cache-Control': 'public, max-age=60',
      },
    });
  },
};
