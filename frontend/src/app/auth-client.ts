export type LoginRequest = { email: string; password: string };
export type RegisterRequest = LoginRequest & { username: string };
export type AuthUser = {
  id: string;
  email: string;
  username: string;
  role: "USER" | "ADMIN";
  createdAt: string;
};

export class AuthError extends Error {
  constructor(message: string, public status?: number) {
    super(message);
  }
}

async function readResponse<T>(response: Response): Promise<T> {
  const isJson = /\bjson\b|\+json\b/i.test(response.headers.get("content-type") ?? "");
  const raw = await response.text();
  let data: unknown = null;
  if (isJson && raw) {
    try { data = JSON.parse(raw); }
    catch { console.error("Invalid auth response:", response.status, raw); }
  } else if (raw && !response.ok) {
    console.error("Auth request failed:", response.status, raw);
  }
  if (!response.ok) {
    const detail = data && typeof data === "object" && "detail" in data && typeof data.detail === "string"
      && response.status < 500 ? data.detail : null;
    const fallback = response.status === 401 ? "Неверный адрес почты или пароль."
      : response.status === 409 ? "Этот адрес почты уже зарегистрирован."
      : response.status === 503 ? "Авторизация недоступна. Проверьте, что backend запущен с PostgreSQL."
      : "Не удалось выполнить запрос. Проверьте данные и попробуйте снова.";
    throw new AuthError(detail ?? fallback, response.status);
  }
  if (!data || typeof data !== "object") throw new AuthError("Сервер вернул некорректный ответ.");
  return data as T;
}

async function sendAuthRequest(path: string, payload: LoginRequest | RegisterRequest) {
  let response: Response;
  try {
    response = await fetch(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "same-origin",
      body: JSON.stringify(payload),
    });
  } catch {
    throw new AuthError("Не удалось связаться с сервером. Попробуйте позже.");
  }
  return readResponse<{ user: AuthUser }>(response);
}

export function login(payload: LoginRequest) {
  return sendAuthRequest("/api/session/login", payload);
}

export function register(payload: RegisterRequest) {
  return sendAuthRequest("/api/session/register", payload);
}

export async function getCurrentUser(): Promise<AuthUser | null> {
  const response = await fetch("/api/session/me", { credentials: "same-origin", cache: "no-store" });
  if (response.status === 401) return null;
  return readResponse<AuthUser>(response);
}

export async function logout() {
  const response = await fetch("/api/session/logout", { method: "POST", credentials: "same-origin" });
  await readResponse<{ ok: true }>(response);
}
