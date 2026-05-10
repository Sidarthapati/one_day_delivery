# M1 Auth — Open Product Questions

Short, scenario-based questions that need a product owner decision before we build.

---

## Q3 — How does a Delivery Associate log in?

Ravi is a new DA in Mumbai. He doesn't have a work email — just a personal phone number. Does he log into the app with:

- His **phone number + OTP** (like Zomato/Swiggy), or
- An **email + password** that the Station Manager sets up for him?

**Decide:** Phone OTP, or email + password for field staff?

---

## Q4 — How does a new DA get their first password?

Arjun creates a DA account in the system. The DA needs a password to log in for the first time.

Does Arjun:
- **Tell them in person / over a call**, or
- Does the system **send it via WhatsApp or SMS** automatically?

**Decide:** Is first-credential delivery the Station Manager's problem, or does the platform handle it?

---

## Q5 — Does a DA account go live immediately, or only after ID verification?

Swiggy and Zomato require Aadhaar + police verification before a DA account is activated.

For 1DD — when Arjun creates a DA account, is the DA:
- **Active immediately** (trust that Arjun has done background checks offline), or
- **Pending** until someone in ops marks their ID as verified?

**Decide:** Is background verification the platform's job, or HR's job before Arjun even creates the account?

---

## Q6 — Can a call center agent in Hyderabad handle a Delhi customer's complaint?

A customer in Delhi calls support at 2 PM. The only free agent is sitting in the Hyderabad call center.

Can they pick it up, or must the call wait for a Delhi-based agent?

**Decide:** Is the call center one central team (all cities), or a separate team per city?

---

## Q7 — A CRON driver calls in sick. Can the backup driver from another city cover?

The BLR CRON driver is sick. The nearest available backup is a driver currently assigned to HYD.

Can the HYD driver legally cover the BLR run for one night, or does ops sort this out entirely offline without touching the system?

**Decide:** Do we need to support temporary cross-city driver assignments in the platform?

---

## Q8 — What do we call the 5 cities, and is Mumbai "MUM" or "BOM"?

Every staff account is locked to a city using a short code. We use `BLR`, `DEL`, `MUM` in examples — but these aren't finalised.

- Mumbai's IATA code is `BOM`. Chennai's is `MAA`. Do we use IATA, or our own codes?
- What are the exact codes for all 5 launch cities?
- If we expand to a 6th city later, is that a product decision or does it need an engineering release?

**Decide:** Confirm the 5 city codes and who controls adding new ones.

---

## Q9 — What if a hub operator's session expires at 4 AM mid-shift?

Hub operators and CRON drivers often work overnight (10 PM – 6 AM) to process early morning flights. If someone logs in at 9 PM, their 8-hour session runs out at 5 AM — right in the middle of peak scan activity.

Do they:
- **Log in again mid-shift** (acceptable UX for ops staff?), or
- Should overnight roles get a **longer session** that covers the full shift?

**Decide:** Must workers log in at the start of every physical shift, or do overnight roles need extended sessions?

---

## Q10 — Does FarhanKart get one login or two?

FarhanKart signs up as a B2B customer. Their ops head books shipments daily. Their accounts head needs to view invoices. Both need access.

- Do they **share one B2B login**, or can FarhanKart have **two separate logins** (one per person)?
- When we invoice FarhanKart, we need their GSTIN. Does the **platform collect it**, or is that handled outside (contracts, billing team)?

**Decide:** One login per business, or multiple? And who owns GSTIN collection?

---

## Q11 — Is it OK for two DAs to share one phone and one login?

In many Indian logistics hubs, one company phone is handed from the morning DA to the evening DA at shift change — still logged in.

Is this:
- **Fine for v1** (we accept that some scan events may be attributed to the wrong person), or
- **Not acceptable** (each DA must log in with their own credentials at the start of every shift)?

**Decide:** Is per-person login a hard requirement, or a v2 concern?

---

## Q12 — Can a Station Manager onboard 25 Diwali DAs in one go?

During Diwali, the BLR hub needs 25 extra DAs in a single day. Today the system creates one account at a time.

- Do we need a **bulk upload** (e.g., a CSV of names and phone numbers), or is one-by-one acceptable for v1?
- Should these seasonal accounts **auto-expire** after Diwali, or does the SM manually deactivate each one?

**Decide:** Bulk creation in v1? Auto-expiry for temporary accounts?

---

## Q13 — How does the admin know which GHA accounts belong to IndiGo vs Air India?

Mumbai airport has GHA staff from multiple airlines. If IndiGo switches their handling agent, we need to deactivate the old accounts and create new ones.

Right now all GHA accounts look identical in the system — there's no airline label on them.

Does the admin:
- Track this **manually** (naming convention like "IndiGo-MUM-Rajan"), or
- Should the platform let you **tag a GHA account with an airline** so you can filter and bulk-deactivate by airline when a contract changes?

**Decide:** Is airline grouping on GHA accounts needed for v1?
