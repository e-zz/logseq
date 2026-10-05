const http = require("http");
const { McpServer } = require("@modelcontextprotocol/sdk/server/mcp.js");
const { StreamableHTTPServerTransport } = require("@modelcontextprotocol/sdk/server/streamableHttp.js");
const { isInitializeRequest } = require("@modelcontextprotocol/sdk/types.js");
const { Client } = require("@modelcontextprotocol/sdk/client/index.js");
const { StreamableHTTPClientTransport } = require("@modelcontextprotocol/sdk/client/streamableHttp.js");
const z = require("zod/v3");

const transports = {};

function createServer(apiFn) {
  const s = new McpServer({ name: "smoke", version: "0" });
  s.registerTool("recycleBlock", { title: "Recycle", inputSchema: { blockUuid: z.string().uuid() } }, async (args) => {
    const body = await apiFn("logseq.cli.recycleBlock", [args.blockUuid]);
    return { content: [{ type: "text", text: JSON.stringify(body) }] };
  });
  s.registerTool("getRecycledBlock", { title: "Get", inputSchema: { blockUuid: z.string().uuid() } }, async (args) => {
    const body = await apiFn("logseq.cli.getRecycledBlock", [args.blockUuid]);
    return { content: [{ type: "text", text: JSON.stringify(body) }] };
  });
  return s;
}

const apiFn = async (method, args) => {
  if (method === "logseq.cli.recycleBlock") {
    return { operation: "recycle", state: "recycled", rootUuid: args[0], affectedCount: 2 };
  }
  if (method === "logseq.cli.getRecycledBlock") {
    return { operation: "get-recycled", state: "recycled", subtree: [args[0]] };
  }
  return { error: "no method " + method };
};

function shimRes(raw) {
  const res = { raw, headers: {} };
  res.getHeaders = () => res.headers;
  res.code = (c) => { res._code = c; return res; };
  res.send = (payload) => {
    raw.writeHead(res._code || 200, { "content-type": "application/json" });
    raw.end(typeof payload === "string" ? payload : JSON.stringify(payload));
    return res;
  };
  return res;
}
function shimReq(raw, body) {
  return { raw, headers: raw.headers, body };
}

function readBody(req) {
  return new Promise((resolve) => {
    let s = "";
    req.on("data", (c) => (s += c.toString("utf8")));
    req.on("end", () => resolve(s ? JSON.parse(s) : undefined));
  });
}

function main() {
  return new Promise((resolve) => {
    const server = http.createServer(async (req, res) => {
      const url = req.url || "";
      if (!url.startsWith("/mcp")) {
        res.writeHead(404); res.end("nope"); return;
      }
      const addr = server.address();
      const port = addr.port;
      if (req.method === "POST") {
        const body = await readBody(req);
        const sessionId = req.headers["mcp-session-id"];
        const existing = sessionId && transports[sessionId];
        if (existing) {
          await existing.handleRequest(req, res, body);
        } else if (!sessionId && isInitializeRequest(body)) {
          const transport = new StreamableHTTPServerTransport({
            sessionIdGenerator: () => require("crypto").randomUUID(),
            enableDnsRebindingProtection: true,
            allowedHosts: [`127.0.0.1:${port}`],
          });
          const mcp = createServer(apiFn);
          transport.onclose = () => { delete transports[transport.sessionId]; };
          await mcp.connect(transport);
          await transport.handleRequest(req, res, body);
          if (transport.sessionId) transports[transport.sessionId] = transport;
        } else {
          res.writeHead(400, { "content-type": "application/json" });
          res.end(JSON.stringify({ jsonrpc: "2.0", error: { code: -32000, message: "Bad Request" }, id: null }));
        }
      } else if (req.method === "GET") {
        const sessionId = req.headers["mcp-session-id"];
        const existing = sessionId && transports[sessionId];
        if (existing) await existing.handleRequest(req, res);
        else { res.writeHead(400); res.end("bad session"); }
      } else if (req.method === "DELETE") {
        const sessionId = req.headers["mcp-session-id"];
        const existing = sessionId && transports[sessionId];
        if (existing) { await existing.close(); res.writeHead(200); res.end(JSON.stringify({ ok: true })); }
        else { res.writeHead(400); res.end("bad session"); }
      } else {
        res.writeHead(405); res.end();
      }
    });
    server.listen(0, "127.0.0.1", async () => {
      const port = server.address().port;
      const transport = new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`));
      const client = new Client({ name: "smoke-client", version: "0" });
      try {
        await client.connect(transport);
        const tools = await client.listTools();
        console.log("TOOLS:", tools.tools.map((t) => t.name).sort().join(","));
        const uuid = "67e55044-10b1-426f-9247-bb680e5fe0c8";
        const r1 = await client.callTool({ name: "recycleBlock", arguments: { blockUuid: uuid } });
        console.log("CALL recycleBlock:", r1.content[0].text);
        const r2 = await client.callTool({ name: "getRecycledBlock", arguments: { blockUuid: uuid } });
        console.log("CALL getRecycledBlock:", r2.content[0].text);
        const bad = await client.callTool({ name: "recycleBlock", arguments: { blockUuid: "not-a-uuid" } });
        console.log("SCHEMA rejected:", JSON.stringify(bad).slice(0, 120));
        await client.close();
        server.close(() => resolve(0));
      } catch (e) {
        console.error("FAIL", e);
        server.close(() => resolve(1));
      }
    });
  });
}

main().then((code) => process.exit(code));
