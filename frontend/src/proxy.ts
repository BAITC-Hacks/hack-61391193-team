import { NextRequest, NextResponse } from "next/server";

const cookieName = "akim_access_token";
const backendOrigin = (process.env.BACKEND_ORIGIN ?? "http://127.0.0.1:8080").replace(/\/$/, "");

export async function proxy(request: NextRequest) {
  const token = request.cookies.get(cookieName)?.value;
  let authorized = false;
  let invalidToken = false;
  if (token) {
    try {
      const response = await fetch(`${backendOrigin}/api/v1/auth/me`, {
        headers: { Authorization: `Bearer ${token}` },
        cache: "no-store",
      });
      authorized = response.ok;
      invalidToken = response.status === 401 || response.status === 403;
    } catch (error) {
      console.error("Session verification could not reach backend", backendOrigin, error instanceof Error ? error.message : "Unknown error");
      // The simulator must not render before the session can be verified.
    }
  }
  if (authorized) return NextResponse.next();

  const redirect = NextResponse.redirect(new URL("/login", request.url));
  redirect.headers.set("Cache-Control", "no-store");
  if (invalidToken) redirect.cookies.delete(cookieName);
  return redirect;
}

export const config = { matcher: ["/"] };
