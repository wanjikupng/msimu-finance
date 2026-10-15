// Offline unlock-code verification, run by the device's lock agent (here: the
// phone simulator). Mirrors UnlockCodeCodec.java exactly:
//   code = SSS DDD MMMMMM
//   SSS  sequence (001-999); the device only accepts a higher one than last
//   DDD  unlocked-until as days after the anchor date; 999 = permanent
//   MMMMMM HMAC-SHA256(key, "MSIMU-UNLOCK-V1|<deviceCode>|SSSDDD") reduced
//          to 6 digits with RFC 4226 dynamic truncation
// Known-answer vector (key = bytes 0x00..0x1f, device TRC-000131):
//   seq 7, days 45  -> 007045352103
//   seq 12, owned   -> 012999742741
// No network access: verification only needs the provisioned key.

export const PERMANENT = 999;
const DOMAIN = "MSIMU-UNLOCK-V1";

function hexToBytes(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}

async function mac(secretHex, deviceCode, payload) {
  const key = await crypto.subtle.importKey("raw", hexToBytes(secretHex), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const h = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`${DOMAIN}|${deviceCode}|${payload}`)));
  const o = h[h.length - 1] & 0x0f;
  const binary = ((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(binary % 1000000).padStart(6, "0");
}

// Returns { sequence, days, permanent } for a genuine code, otherwise null.
export async function verifyCode(secretHex, deviceCode, typed) {
  const digits = String(typed ?? "").replace(/[\s-]/g, "");
  if (!/^\d{12}$/.test(digits)) return null;
  const payload = digits.slice(0, 6);
  const expected = await mac(secretHex, deviceCode, payload);
  let diff = 0;
  for (let i = 0; i < 6; i++) diff |= expected.charCodeAt(i) ^ digits.charCodeAt(6 + i);
  if (diff !== 0) return null;
  const sequence = Number(payload.slice(0, 3));
  const days = Number(payload.slice(3, 6));
  if (sequence < 1) return null;
  return { sequence, days, permanent: days === PERMANENT };
}

export function formatCode(code) {
  const d = String(code).replace(/\D/g, "");
  return `${d.slice(0, 4)} ${d.slice(4, 8)} ${d.slice(8, 12)}`;
}
