// Embeds the M1/M2/M4 static console (auth · pricing · orders · cart · admin) served by the backend
// at :8080/. It runs as its own app inside the iframe — its /api calls are same-origin to :8080, so
// everything (login, quote, booking, cart, admin) works exactly as standalone. The demo backend drops
// X-Frame-Options for the static paths (DemoSecurityConfig) so this frame is allowed.
const CONSOLE_URL = `http://${window.location.hostname}:8080/`

export default function BookingConsole() {
  return (
    <div className="flex-1 relative bg-white">
      <iframe
        title="Booking & Ops console (M1 · M2 · M4)"
        src={CONSOLE_URL}
        className="absolute inset-0 w-full h-full border-0"
      />
    </div>
  )
}
