import type { NextRequest } from "next/server";
import { currentUser } from "../session-server";

export async function GET(request: NextRequest) {
  return currentUser(request);
}
