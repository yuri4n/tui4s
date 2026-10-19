# Nuxt server: less common methods (pasted by the user from nuxi, 2026-10-19)

<!-- claude 2026-10-19: created this file from the user's paste; the text below is the user's source, kept verbatim as reference for the h34s rounds (task #26) -->

Source note: nuxt.com is blocked from the cloud container, so this paste is the
reference for the Nuxt-level philosophy; the mechanics come from node_modules
(nitropack, h3) in the h34s repo.

## sendStream
Use when the body is too large to buffer or needs progressive delivery. Accepts a
Node Readable or a web ReadableStream. `return sendStream(event, stream)`.
Returning the stream directly also works in h3 v2+. Experimental on edge runtimes.

## sendRedirect
`await sendRedirect(event, '/path', 302)`; 301 for permanent.

## createEventStream (SSE)
`const stream = createEventStream(event); stream.push('ping'); stream.onClosed(...); return stream.send()`.
Prefer SSE over WebSockets for server-to-client only (dashboards, job progress,
notifications): plain HTTP, no upgrade, browser reconnects.

## handleCors / appendCorsHeaders
`handleCors(event, { origin, preflight: { statusCode: 204 }, methods: '*' })` answers
the OPTIONS preflight and appends the headers on real requests; `appendCorsHeaders`
is the header-only primitive. CORS is added at runtime by the server.

## proxyRequest / sendProxy
`return proxyRequest(event, 'https://api.third-party.com/data')`: forward to an
upstream while keeping credentials server-side; h3 strips unsafe forwarding headers.
`sendProxy` is the simpler alias.

## $fetch inside server routes
Powered by ofetch, auto-imported. Inside a server route, calls to the app's own
routes resolve in-process (Nitro "direct call": no HTTP round trip). Use
`event.$fetch` to forward the incoming request context (cookies, headers).
In Vue components wrap `$fetch` in `useAsyncData` or use `useFetch`.

## setResponseStatus
`setResponseStatus(event, 202); return { queued: true }`. A return value alone is 200.
Use `createError({ status })` for 4xx/5xx.

## useRuntimeConfig(event)
Always pass `event` in server routes, so Nitro applies runtime environment overrides
at request time.

## Key mental model (the return value contract)
| Return value | What happens |
|---|---|
| Plain object/array | JSON, 200 |
| String | text, 200 |
| null / undefined | 204 No Content |
| Node/Web Stream | streamed response |
| Response object | passed through as is (h3 v2+) |
| throw createError() | HTTP error with status and message |
