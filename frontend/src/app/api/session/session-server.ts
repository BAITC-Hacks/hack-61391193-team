import { NextRequest, NextResponse } from "next/server";

const cookieName = "akim_access_token";
const backendOrigin = (process.env.BACKEND_ORIGIN ?? "http://127.0.0.1:8080").replace(/\/$/, "");

type AuthUser = {
  id: string;
  email: string;
  username: string;
  role: "USER" | "ADMIN";
  createdAt: string;
};

type AuthResponse = {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: AuthUser;
};

function noStore(response: NextResponse) {
  response.headers.set("Cache-Control", "no-store");
  return response;
}

function unavailable() {
  return noStore(NextResponse.json({ detail: "Не удалось связаться с сервером авторизации." }, { status: 503 }));
}

async function backendError(response: Response) {
  const body = await response.json().catch(() => null);
  return noStore(NextResponse.json(
    body && typeof body === "object" ? body : { detail: "Не удалось выполнить запрос." },
    { status: response.status },
  ));
}

function isHttps(request: NextRequest) {
  return request.nextUrl.protocol === "https:" || request.headers.get("x-forwarded-proto") === "https";
}

function clearSession(response: NextResponse, request: NextRequest) {
  response.cookies.set(cookieName, "", {
    httpOnly: true,
    sameSite: "lax",
    secure: isHttps(request),
    path: "/",
    maxAge: 0,
  });
  return noStore(response);
}

export async function authenticate(request: NextRequest, mode: "login" | "register") {
  const body = await request.json().catch(() => null);
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    return noStore(NextResponse.json({ detail: "Некорректные данные формы." }, { status: 400 }));
  }

  let response: Response;
  try {
    response = await fetch(`${backendOrigin}/api/v1/auth/${mode}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      cache: "no-store",
    });
  } catch {
    return unavailable();
  }
  if (!response.ok) return backendError(response);

  const session = await response.json().catch(() => null) as AuthResponse | null;
  if (!session || typeof session.accessToken !== "string" || session.tokenType !== "Bearer"
      || !Number.isFinite(session.expiresIn) || session.expiresIn <= 0 || !session.user) {
    return unavailable();
  }

  const result = noStore(NextResponse.json({ user: session.user }, { status: response.status }));
  result.cookies.set(cookieName, session.accessToken, {
    httpOnly: true,
    sameSite: "lax",
    secure: isHttps(request),
    path: "/",
    maxAge: session.expiresIn,
  });
  return result;
}

export async function currentUser(request: NextRequest) {
  const token = request.cookies.get(cookieName)?.value;
  if (!token) {
    return noStore(NextResponse.json({ detail: "Требуется вход в аккаунт." }, { status: 401 }));
  }

  let response: Response;
  try {
    response = await fetch(`${backendOrigin}/api/v1/auth/me`, {
      headers: { Authorization: `Bearer ${token}` },
      cache: "no-store",
    });
  } catch {
    return unavailable();
  }
  if (!response.ok) {
    const error = await backendError(response);
    return response.status === 401 || response.status === 403 ? clearSession(error, request) : error;
  }
  return noStore(NextResponse.json(await response.json()));
}

export function logout(request: NextRequest) {
  return clearSession(NextResponse.json({ ok: true }), request);
}
