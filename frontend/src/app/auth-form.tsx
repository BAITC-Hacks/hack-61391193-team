"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { AuthError, login, register } from "./auth-client";

type Mode = "login" | "register";

export default function AuthForm({ mode }: { mode: Mode }) {
  const router = useRouter();
  const isRegister = mode === "register";
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    const fields = new FormData(event.currentTarget);
    const email = String(fields.get("email") ?? "").trim();
    const password = String(fields.get("password") ?? "");

    if (isRegister && password !== fields.get("confirmPassword")) {
      setError("Пароли не совпадают.");
      return;
    }
    if (isRegister && new TextEncoder().encode(password).length > 72) {
      setError("Пароль должен занимать не более 72 байт.");
      return;
    }

    setPending(true);
    try {
      if (isRegister) {
        await register({ username: String(fields.get("username") ?? "").trim(), email, password });
      } else {
        await login({ email, password });
      }
      router.replace("/");
      router.refresh();
    } catch (cause) {
      setError(cause instanceof AuthError ? cause.message : "Что-то пошло не так. Попробуйте снова.");
    } finally {
      setPending(false);
    }
  }

  return (
    <main className="auth-screen">
      <div className="auth-shell">
        <section className="auth-intro" aria-label="О платформе">
          <Link href="/" className="auth-logo">Астана <span>·</span> Районы</Link>
          <div>
            <span className="auth-eyebrow">ГОРОДСКАЯ ПЛАТФОРМА</span>
            <h1>Исследуйте город и принимайте решения осознанно.</h1>
            <p>Карта районов Астаны и инструменты для работы с городскими данными в одном месте.</p>
          </div>
          <span className="auth-intro-footer">Астана · Казахстан</span>
        </section>

        <section className="auth-panel" aria-labelledby="auth-title">
          <div className="auth-card">
            <div className="auth-tabs" aria-label="Авторизация">
              <Link href="/login" aria-current={!isRegister ? "page" : undefined}>Вход</Link>
              <Link href="/register" aria-current={isRegister ? "page" : undefined}>Регистрация</Link>
            </div>

                <h2 id="auth-title">{isRegister ? "Создать аккаунт" : "С возвращением"}</h2>
                <p className="auth-subtitle">{isRegister
                  ? "Заполните данные, чтобы начать работу с платформой."
                  : "Введите данные, чтобы продолжить работу."}</p>
                <form onSubmit={handleSubmit} className="auth-form">
                  {isRegister && (
                    <label>
                      Имя
                      <input name="username" type="text" autoComplete="nickname" placeholder="Ваше имя" required minLength={3} maxLength={50} />
                    </label>
                  )}
                  <label>
                    Электронная почта
                    <input name="email" type="email" autoComplete="email" placeholder="name@example.com" required maxLength={254} />
                  </label>
                  <label>
                    Пароль
                    <input name="password" type="password" autoComplete={isRegister ? "new-password" : "current-password"}
                      placeholder={isRegister ? "Не менее 8 символов" : "Введите пароль"} required minLength={isRegister ? 8 : undefined} maxLength={72} />
                  </label>
                  {isRegister && (
                    <label>
                      Повторите пароль
                      <input name="confirmPassword" type="password" autoComplete="new-password" placeholder="Повторите пароль" required minLength={8} maxLength={72} />
                    </label>
                  )}
                  {error && <p className="auth-error" role="alert">{error}</p>}
                  <button className="auth-submit" type="submit" disabled={pending}>
                    {pending ? "Подождите…" : isRegister ? "Создать аккаунт" : "Войти"}
                  </button>
                </form>
                <p className="auth-switch">{isRegister ? "Уже есть аккаунт?" : "Нет аккаунта?"} <Link href={isRegister ? "/login" : "/register"}>{isRegister ? "Войти" : "Зарегистрироваться"}</Link></p>
            <Link href="/" className="auth-back">← Вернуться к карте</Link>
          </div>
        </section>
      </div>
    </main>
  );
}
