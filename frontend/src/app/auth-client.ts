export type LoginRequest = { email: string; password: string };
export type RegisterRequest = LoginRequest & { name: string };

export class AuthError extends Error {}

async function sendAuthRequest(path: string, payload: LoginRequest | RegisterRequest) {
  let response: Response;
  try {
    response = await fetch(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify(payload),
    });
  } catch {
    throw new AuthError("Не удалось связаться с сервером. Попробуйте позже.");
  }

  if (response.ok) return;
  if (response.status === 404 || response.status === 503) {
    throw new AuthError("Авторизация пока не подключена. Попробуйте позже.");
  }

  const data = await response.json().catch(() => null);
  const detail = data && typeof data === "object" && "detail" in data && typeof data.detail === "string"
    ? data.detail
    : null;
  throw new AuthError(detail ?? (response.status === 401
    ? "Неверный адрес почты или пароль."
    : "Не удалось выполнить запрос. Проверьте данные и попробуйте снова."));
}

export function login(payload: LoginRequest) {
  return sendAuthRequest("/api/v1/auth/login", payload);
}

export function register(payload: RegisterRequest) {
  return sendAuthRequest("/api/v1/auth/register", payload);
}
