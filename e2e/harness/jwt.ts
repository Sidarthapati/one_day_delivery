/** Decode the `sub` (user id) from a JWT without verifying — we already trust the issuer here. */
export function jwtSub(token: string): string {
  const parts = token.split('.');
  if (parts.length < 2) throw new Error('not a JWT');
  const payload = JSON.parse(Buffer.from(parts[1]!, 'base64url').toString('utf8'));
  if (!payload.sub) throw new Error('JWT has no sub');
  return String(payload.sub);
}
