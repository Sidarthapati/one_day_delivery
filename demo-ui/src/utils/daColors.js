export const DA_COLORS = [
  '#e6194b', '#3cb44b', '#4363d8', '#f58231', '#911eb4',
  '#42d4f4', '#f032e6', '#bfef45', '#fabed4', '#469990',
  '#dcbeff', '#9A6324',
]

export function hashDaColor(daId) {
  if (!daId) return '#e5e7eb'
  let h = 0
  for (let i = 0; i < 4 && i < daId.length; i++) {
    h = (h * 31 + daId.charCodeAt(i)) & 0xffff
  }
  return DA_COLORS[h % DA_COLORS.length]
}
