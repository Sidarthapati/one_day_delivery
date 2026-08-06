// cities.js — the 5 grid cities. hub_id == city_id in v1 (hub design §14.3), and these are the
// exact same fixed UUIDs already seeded in app/src/main/resources/application.yml under
// grid.cities / airline.cities — kept in sync manually since there's no API to fetch them.
const CITIES = [
  { code: 'DEL', name: 'Delhi',     hubId: 'f47ac10b-58cc-4372-a567-0e02b2c3d479' },
  { code: 'BOM', name: 'Mumbai',    hubId: '550e8400-e29b-41d4-a716-446655440000' },
  { code: 'BLR', name: 'Bangalore', hubId: '6ba7b810-9dad-11d1-80b4-00c04fd430c8' },
  { code: 'HYD', name: 'Hyderabad', hubId: '6ba7b811-9dad-11d1-80b4-00c04fd430c8' },
  { code: 'MAA', name: 'Chennai',   hubId: '6ba7b812-9dad-11d1-80b4-00c04fd430c8' },
];

function cityByCode(code) {
  return CITIES.find(c => c.code === code);
}

/** Fills a <select> with the 5 cities (value = IATA code). */
function fillCitySelect(selectEl) {
  selectEl.innerHTML = CITIES.map(c => `<option value="${c.code}">${c.name} (${c.code})</option>`).join('');
}
