// cityCode → label, map center, and the fixed cityId UUID shared M3 ↔ M6 (routing endpoints take
// the UUID; grid endpoints take the code). Kept in sync with app application.yml grid.cities.
export const CITIES = {
  delhi:     { label: 'Delhi',     center: [28.6139, 77.2090], id: 'f47ac10b-58cc-4372-a567-0e02b2c3d479' },
  mumbai:    { label: 'Mumbai',    center: [19.0760, 72.8777], id: '550e8400-e29b-41d4-a716-446655440000' },
  bangalore: { label: 'Bangalore', center: [12.9716, 77.5946], id: '6ba7b810-9dad-11d1-80b4-00c04fd430c8' },
  hyderabad: { label: 'Hyderabad', center: [17.3850, 78.4867], id: '6ba7b811-9dad-11d1-80b4-00c04fd430c8' },
  chennai:   { label: 'Chennai',   center: [13.0827, 80.2707], id: '6ba7b812-9dad-11d1-80b4-00c04fd430c8' },
}

// Single plan date for the whole demo = tomorrow (the nightly "plan for tomorrow" semantics M6
// defaults to). All steps — seed, M3 replan/approve/activate, M6 replan/approve — use this date.
const _t = new Date(Date.now() + 24 * 3600 * 1000)
export const PLAN_DATE = `${_t.getFullYear()}-${String(_t.getMonth() + 1).padStart(2, '0')}-${String(_t.getDate()).padStart(2, '0')}`
