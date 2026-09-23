import type { NextRequest } from "next/server";
import { authenticate } from "../session-server";

export async function POST(request: NextRequest) {
  return authenticate(request, "register");
}
