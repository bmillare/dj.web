# Context: Datastar / dj.web Architecture

This project uses Datastar. It completely rejects standard SPA (React/Vue) and REST/JSON API paradigms. Treat the browser as a dumb, immediate-mode renderer. The server owns 100% of the state, routing, and UI logic. 

If you are asked to write code, adhere strictly to the following rules. Do not invent client-side state synchronization, component lifecycles, or JSON APIs.

## 1. HTML Over The Wire (No JSON)
* **Rule:** The server responds to interactions with raw HTML fragments, never JSON.
* **Mechanism:** The client uses Datastar to intercept requests and morph the DOM.
* **Fat Morphs > Granular Targeting:** Do not write hyper-specific endpoints for tiny components. Re-render large stable fragments (like `<main>`) and push them over the wire. Let the client-side `idiomorph` handle the DOM diffing.

## 2. SSE & CQRS (The Single Source of Truth)
* **Rule:** Separate Commands (writes) from Queries (reads).
* **Commands:** Form submissions or button clicks should trigger actions that mutate the database and return an empty `204 No Content`. *Do not return UI updates from write actions.*
* **Queries:** A single long-lived Server-Sent Events (SSE) endpoint watches the database. When state changes, it re-renders the HTML view (`view = f(state)`) and broadcasts it down the SSE stream to the client.

## 3. Locality of Behavior (No Vanilla JS Listeners)
* **Rule:** UI logic belongs in the HTML attributes, not in separate `.js` files. 
* **Mechanism:** Use Datastar's `data-on:*` attributes. 
* **Example:** Use `<button data-on:click="@post('/action')">` instead of writing `document.getElementById(...).addEventListener(...)`. 
* **Event Bubbling:** To avoid attaching thousands of listeners to lists/grids, attach a single `data-on:click` to the parent and read standard `data-*` attributes from the bubbled event.

## 4. Signals Are Strictly for Ephemeral UI State
* **Rule:** NEVER put backend domain data (like database models) into client-side signals. Domain state belongs on the server.
* **Usage:** Use signals *only* for transient client state: text input drafts, popover visibility, or client-side validation.
* **Safety:** Almost always use the `__ifmissing` modifier (`data-signals__ifmissing="{...}"`) when declaring signals in the DOM so that server-rendered HTML morphs don't accidentally overwrite active user input.

## 5. Native Web > JS Abstractions
* **Rule:** Do not write JS for things the browser does natively.
* **Navigation:** For actual page transitions, use standard `<a>` tags and full page reloads. Reserve Datastar strictly for in-page updates.
* **UI Polish:** Use native CSS animations, native `data-on:pointerdown` (faster than `click`), and standard HTML/ARIA semantics. Do not build optimistic UI; use simple `data-indicator` loading states while the server processes the SSE round-trip.

## 6. Exceptional Integration Seams
* If you absolutely must integrate with an opaque 3rd-party JS library (e.g., charts) or protect an element from being overwritten by the server's HTML morph:
  * Use `data-ignore-morph` to protect subtrees.
  * Use `data-preserve-attr` for specific attributes.
  * Use a vanilla Web Component for complex client-side lifecycle needs, driven by Datastar signals.
