(() => {
  const $ = (id) => document.getElementById(id);

  const rpcUrlInput = $("rpcUrl");
  const statusGrid = $("statusGrid");
  const statusError = $("statusError");
  const blocksBody = $("blocksBody");
  const detailOut = $("detailOut");

  let nextId = 1;

  function rpcUrl() {
    return (rpcUrlInput.value || "http://127.0.0.1:10001").replace(/\/$/, "");
  }

  async function rpc(method, params = []) {
    // XDAGJ JsonRpcRequest.id is Java int — Date.now() overflows and yields HTTP 500.
    const id = nextId++;
    if (nextId > 1_000_000_000) nextId = 1;
    const res = await fetch(rpcUrl(), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", method, params, id }),
    });
    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`);
    }
    const body = await res.json();
    if (body.error) {
      throw new Error(body.error.message || JSON.stringify(body.error));
    }
    return body.result;
  }

  function fmtTime(ms) {
    if (ms == null || ms === 0) return "—";
    try {
      return new Date(Number(ms)).toISOString().replace("T", " ").replace(/\.\d+Z$/, " UTC");
    } catch {
      return String(ms);
    }
  }

  function shortHash(h) {
    if (!h) return "—";
    const s = String(h);
    return s.length <= 18 ? s : `${s.slice(0, 10)}…${s.slice(-6)}`;
  }

  function showDetail(obj) {
    detailOut.textContent = typeof obj === "string" ? obj : JSON.stringify(obj, null, 2);
  }

  // A single full-width table row for empty/error states — built as a node, never HTML.
  function messageRow(text) {
    const tr = document.createElement("tr");
    const td = document.createElement("td");
    td.colSpan = 5;
    td.className = "muted";
    td.textContent = text;
    tr.appendChild(td);
    return tr;
  }

  function renderStatus(status, height, netType) {
    const rows = [
      ["netType", netType],
      ["blockNumber", height],
      ["nmain", status?.nmain],
      ["totalNmain", status?.totalNmain],
      ["nblock", status?.nblock],
      ["totalNblocks", status?.totalNblocks],
      ["curDiff", status?.curDiff],
      ["netDiff", status?.netDiff],
      ["hashRateOurs", status?.hashRateOurs],
      ["hashRateTotal", status?.hashRateTotal],
      ["ourSupply", status?.ourSupply],
      ["netSupply", status?.netSupply],
    ];
    // Build with textContent, never innerHTML: RPC-sourced values must never be parsed as HTML.
    statusGrid.replaceChildren();
    for (const [k, v] of rows) {
      const dt = document.createElement("dt");
      dt.textContent = k;
      const dd = document.createElement("dd");
      dd.textContent = v == null || v === "" ? "—" : String(v);
      statusGrid.append(dt, dd);
    }
  }

  function renderBlocks(blocks) {
    if (!blocks || blocks.length === 0) {
      blocksBody.replaceChildren(messageRow("No main blocks yet"));
      return;
    }
    blocksBody.replaceChildren();
    for (const b of blocks) {
      const hash = b.hash || b.address || "";
      const tr = document.createElement("tr");
      const cells = [
        [b.height ?? "—", null],
        [shortHash(hash), "hash"],
        [fmtTime(b.blockTime), null],
        [b.balance ?? "—", null],
        [b.state ?? "—", null],
      ];
      for (const [text, cls] of cells) {
        const td = document.createElement("td");
        if (cls) td.className = cls;
        td.textContent = String(text);
        tr.appendChild(td);
      }
      // Capture hash/height in the closure rather than data-* attributes — no HTML sink at all.
      tr.addEventListener("click", () => openDetail(hash, b.height));
      blocksBody.appendChild(tr);
    }
  }

  async function openDetail(hash, height) {
    try {
      let detail;
      if (hash && hash.length >= 16) {
        detail = await rpc("xdag_getBlockByHash", [hash, 1]);
      } else if (height != null && height !== "") {
        detail = await rpc("xdag_getBlockByNumber", [String(height), 1]);
      }
      showDetail(detail);
    } catch (e) {
      showDetail(`Error: ${e.message}`);
    }
  }

  async function refresh() {
    statusError.hidden = true;
    try {
      const [status, height, netType, blocks] = await Promise.all([
        rpc("xdag_getStatus"),
        rpc("xdag_blockNumber"),
        rpc("xdag_netType"),
        rpc("xdag_getBlocksByNumber", ["20"]),
      ]);
      renderStatus(status, height, netType);
      renderBlocks(blocks);
    } catch (e) {
      statusError.hidden = false;
      statusError.textContent = `RPC failed: ${e.message}. Is the node running at ${rpcUrl()}?`;
      statusGrid.replaceChildren();
      blocksBody.replaceChildren(messageRow("Unavailable"));
    }
  }

  function looksLikeHeight(s) {
    return /^\d+$/.test(s);
  }

  function looksLikeHash(s) {
    return /^(0x)?[0-9a-fA-F]{32,64}$/.test(s);
  }

  async function lookup(raw) {
    const q = (raw || "").trim();
    if (!q) return;
    try {
      if (looksLikeHeight(q)) {
        showDetail(await rpc("xdag_getBlockByNumber", [q, 1]));
        return;
      }
      if (looksLikeHash(q)) {
        const hash = q.startsWith("0x") ? q.slice(2) : q;
        try {
          showDetail(await rpc("xdag_getBlockByHash", [hash, 1]));
        } catch {
          showDetail(await rpc("xdag_getTransactionByHash", [hash, 1]));
        }
        return;
      }
      // Base58 / address
      const balance = await rpc("xdag_getBalance", [q]);
      showDetail({ address: q, balance });
    } catch (e) {
      showDetail(`Lookup error: ${e.message}`);
    }
  }

  $("btnRefresh").addEventListener("click", refresh);
  $("lookupForm").addEventListener("submit", (ev) => {
    ev.preventDefault();
    lookup($("lookupInput").value);
  });

  refresh();
  setInterval(refresh, 15000);
})();
