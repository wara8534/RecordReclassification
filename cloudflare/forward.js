export async function forwardToJava(request, namespace) {
  try {
    // One stable instance serves HTML, assets and APIs at the same origin.
    // Forward the original stream: do not buffer or log uploaded workbooks.
    return await namespace.getByName("record-on").fetch(request);
  } catch {
    // Never retry a POST automatically: the request body may be consumed.
    return Response.json({
      message: "Java 서버를 시작하거나 연결하는 중입니다. 잠시 후 다시 시도해 주세요."
    }, {
      status: 503,
      headers: { "Cache-Control": "no-store", "Retry-After": "10" }
    });
  }
}
