"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { getCurrentUser, logout, type AuthUser } from "./auth-client";

export default function AuthNav() {
  const router = useRouter();
  const [user, setUser] = useState<AuthUser | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  useEffect(() => {
    let active = true;
    getCurrentUser()
      .then((profile) => { if (active) setUser(profile); })
      .catch(() => { if (active) setUser(null); });
    return () => { active = false; };
  }, []);

  async function handleLogout() {
    setPending(true);
    setError(null);
    try {
      await logout();
      setUser(null);
      router.refresh();
    } catch {
      setError("Не удалось выйти. Попробуйте снова.");
    } finally {
      setPending(false);
    }
  }

  return (
    <nav className="map-auth-links" aria-label="Аккаунт">
      {user === undefined ? <span>Проверяем вход…</span> : user ? (
        <>
          <span title={user.email}>{user.username}</span>
          <button type="button" onClick={handleLogout} disabled={pending}>{pending ? "Выход…" : "Выйти"}</button>
        </>
      ) : (
        <><Link href="/login">Войти</Link><Link href="/register">Регистрация</Link></>
      )}
      {error && <span role="alert" className="auth-nav-error">{error}</span>}
    </nav>
  );
}
