import type { NextRequest } from "next/server";
import { logout } from "../session-server";

export async function POST(request: NextRequest) {
  return logout(request);
}
