# Datastar application guidance

These consolidated guidelines were drawn from Anders Murphy's Hyperlith README
and from comments and blog posts about Datastar. They turn the recurring ideas
in that source material into defaults an application can act on. Linked deep
dives preserve the mechanisms, measurements, and qualifications behind those
defaults.

Treat performance figures as workload reports, not universal guarantees. The
architectural claims are hypotheses to measure against the application being
built.

## Over-arching rule
### Code Must Be Defended by Hard Metrics, Not "Best Practices"
Because Datastar challenges standard web development conventions, "industry standard" arguments don't hold weight.
* **The Insight:** `sudodevnull` emphasizes a strict, data-driven engineering culture: *"If you can't backup your ideas or defend your code with metrics you are gonna have a bad time."* The guideline here is that to build fast Datastar apps, you must measure actual performance (e.g., rendering speed, payload size) rather than relying on theoretical "best practices."

## Immediate-mode HTML & Streaming Compression

The default approach in Datastar is highly unintuitive to developers used to traditional Single Page Applications (SPAs): **render the current view, send a large stable fragment (such as `<main>`) over a long-lived connection, and let the network and client figure out the rest.** 

Do not begin by inventing server-side view diffs, component-specific routes, or client-side state synchronization. Experienced Datastar developers have found that this approach drastically simplifies the backend without the expected performance penalties.

Here is why the standard web development intuition is flipped on its head:

### 1. The "Immediate Mode" Rendering Paradigm
In traditional frontend development, sending the entire DOM over the network for every update is considered a massive performance anti-pattern. Datastar treats the web more like an "immediate mode" GUI in video games.

* **Avoid the Endpoint Explosion:** By defaulting to `outer` morphing and generally targeting the `<main>` element, you drastically simplify your API. You avoid the explosion of hyper-specific endpoints required by other HTML-over-the-wire approaches.
* **Fine-Grained Updates are a Scaling Trap:** Targeting individual elements forces the server to spend computing resources tracking exactly what needs to be updated, where, and for which user. Re-rendering the whole view (or large chunks of it) provides "batching for free."
* **Let Idiomorph do the heavy lifting:** Send the full HTML fragment and rely on the client-side `idiomorph` library to quickly diff and patch the browser DOM.

### 2. Streaming Compression is your "Virtual DOM" Over the Wire
The standard assumption is that streaming an entire `<main>` HTML element every 200ms would absolutely destroy network bandwidth. This ignores the reality of how long-lived HTTP connections work. 

* **The Bandwidth Math is Flipped:** Datastar relies on Server-Sent Events (SSE). Because the connection stays open, algorithms like Brotli, Zstd, or gzip maintain a compression context window across messages. 
* **The Compression "Exploit":** If you send a 65KB HTML "fat morph" frame, and 100ms later send another 65KB frame where only a single checkbox changed, the compression algorithm treats the redundant HTML as a byte-level diff. The resulting packet sent over the wire might be as small as 13 to 20 bytes.
* **Stop worrying about payload size:** Don't waste time minifying class names, shrinking IDs, or trimming HTML payloads pre-emptively. Sending the full HTML snippet is often *more* network-efficient than sending granular JSON updates.

> **Deep Dive:** Compression relies on repeated HTML bytes on the wire; Idiomorph preserves the corresponding browser nodes. Together, they make a coarse `view = f(state)` render the preferred starting point. 
> 
> For the hard math, measured gzip boundaries, contrasting workloads, and the exact escalation order to follow if you actually hit a bottleneck, see **[Streaming compression: mechanism, evidence, and escalation](streaming-compression.md)**.

### 3. Client Performance on Weak Devices and Slow Connections
A common objection to returning HTML blobs instead of minimal JSON is that it will degrade the user experience on slow connections (like 3G) and older devices.

* **Shifting the Burden to the Server:** Slow internet is almost always paired with slow device hardware. React and CSS-in-JS require massive CPU overhead to parse and execute large JavaScript bundles. Sending compressed HTML shifts the computational burden away from the weak client device and back to the server, resulting in a faster time-to-interactive.
* **Connection Priming:** While the initial load might take standard time on a slow network, subsequent interactions are lightning-fast. Because the SSE connection is already open, authenticated, and primed, each user interaction skips standard HTTP handshake overhead.

---

## Server Ownership & The Single Source of Truth

The most difficult mental hurdle when adopting Datastar is unlearning the standard modern web architecture. You are no longer building two separate applications (a frontend UI and a backend API) that constantly sync with one another. In Datastar, the server owns everything: the state, the routing, the UI logic, and the swap behavior. 

### 1. Eradicating the "Two-State" Accidental Complexity
Standard modern web development forces you to build two separate state machines: a backend database that acts as the real source of truth, and a frontend state manager (Redux, React Context, Pinia) to mirror that truth for the UI. Developers spend massive amounts of time and complex code just keeping these two states synchronized.

* **The Unintuitive Reality:** Creating an intricate JavaScript state on the client is an illusion of control and a source of accidental complexity. Because state eventually has to persist to a database, you are *already* managing it on the server.
* **The Insight:** As noted by **JSR_FDED**, dropping the "two-state problem" drastically reduces bugs because you eliminate the dual sources of truth. Furthermore, the idea that the DOM must be micro-managed by a massive JavaScript bundle is a modern fallacy. Browsers are built by immensely talented C++ engineers specifically to render HTML and manage state via hypermedia.
* **The Result:** Moving state entirely to the backend results in explosive performance gains. **Aeolos** reported that migrating from React to Datastar dropped their initial page load from 2.0s to 0.1s, shrank a 750KB JS bundle to just 20KB, and reduced network requests from 40+ to 1.
* **Guideline:** Push 99% of your state to the backend. Use a "Fat Services, Thin Routes" architecture where business logic lives in a core backend library that your UI templates consume.

### 2. `View = f(state)` Belongs on the Network
Leaving React or Vue does *not* mean abandoning their highly successful functional UI models. You are still using the exact same `view = f(state)` paradigm—the only difference is where the execution happens.

* **The Unintuitive Reality:** The client page shouldn't know anything about the data structure or the logic of the application. The standard assumption is that clients need JSON to understand and render state. In Datastar, **the HTML *is* the application state.** 
* **The Insight (from andersmurphy & array_key_first):** The client is intentionally "dumb"—it just morphs the HTML it receives. The developer experience is identical to React's functional components, except that function runs entirely on the backend, and the resulting UI state is piped over the network via Server-Sent Events (SSE).
* **Client State is an Illusion:** Consider a massive billion-row grid. Standard intuition assumes you need a complex client-side virtual DOM array to handle it. In Datastar, those billion items live purely in a backend SQLite database. The server only pushes the HTML for the rows the user is currently viewing, plus a small buffer. As the user scrolls, the server simply evaluates `f(state)` and streams the next pre-rendered view down the pipe. 

### 3. Pushing "Locality of Behavior" to the Backend
Even within the hypermedia/HTML-over-the-wire ecosystem, Datastar challenges existing norms regarding where UI swap logic should live.

* **The Unintuitive Reality:** In libraries like HTMX, the frontend dictates the UI swap. The HTML attributes explicitly tell the browser where to put the server's response (e.g., `hx-target="#div"` and `hx-swap="outerHTML"`). Datastar flips this to be **server-driven**.
* **The Insight:** In Datastar, the client HTML simply triggers an event: `"Hey, I was clicked"` (e.g., `data-on-click="@get('/rebuild')"`). The server responds with an HTML fragment containing an ID, and Datastar implicitly knows to morph the matching element.
* **Guideline:** Keep your client-side attributes incredibly simple. As **sudodevnull** notes, returning a single line of backend code like `datastar.Patch(renderComponent(db.NextRow))` becomes the ultimate Locality of Behavior. The backend dictates exactly what UI updates, removing the need for the client to track targets.

### 4. Tight Coupling is a Feature, Not a Bug
The industry standard dictates that frontends and backends should be strictly decoupled, usually via a heavily typed JSON API (REST/GraphQL), so multiple clients can consume the same endpoints safely. Datastar throws this out the window.

* **The Unintuitive Reality:** If you are building a web application, decoupling the frontend from the backend is usually premature optimization that creates massive friction.
* **The Insight:** As **andersmurphy** explicitly confirms, Datastar abandons decoupled APIs: *"It assumes you're building a full-stack app driven by the backend. So the client and backend are tightly coupled and built for each other."* The backend doesn't serve raw, generic data; it serves HTML fragments deeply tailored to the exact UI the user is looking at.
* **Open Data > Strict Typing:** Standard web dev relies on strict static typing (like TypeScript) across the network boundary to prevent bugs. However, **andersmurphy** argues that strict types across network boundaries can actively hinder refactoring in this architecture. By embracing an open data model (which is how the internet natively works), you only ever have to touch the specific parts of the system that are actively changing, rather than fighting compiler errors across a rigidly bound, globally typed JSON API. Build your application as a single cohesive unit.

## SSE, CQRS, and Trivial Multiplayer

When developers think of real-time server-to-client communication, WebSockets and complex client-side state managers (or heavy stateful backends like Phoenix LiveView) are almost always the default choice. Datastar completely rejects this model. By combining Server-Sent Events (SSE), strict Command Query Responsibility Segregation (CQRS), and time-based batching, real-time "multiplayer" applications become trivial to build and vastly easier to scale.

### 1. The Transport: SSE > WebSockets
*   **The Standard Intuition:** WebSockets are the ultimate tool for real-time, bi-directional web applications.
*   **The Datastar Reality:** Operationally, WebSockets are a scaling nightmare. They suffer from blocked ports, load-balancing difficulties, lack of multiplexing (which can lead to accidental DDoS issues), high mobile battery drain, and no built-in compression.
*   **The Guideline:** **Use SSE.** Because SSE operates over standard HTTP, it inherits multiplexing, header support, built-in compression, and HTTP/2 & HTTP/3 benefits natively. 
    *   *Nuance:* A stream that must reopen after a `200 OK` response later ends (e.g., during a deploy, proxy timeout, or render failure) requires an explicit client policy such as `@get('/updates', {retry: 'always', retryMaxCount: 1000})`. The default `retry: 'auto'` retries a failed fetch but does not reopen a successfully started stream after a clean end.

### 2. The Architecture: True CQRS and the Single SSE Endpoint
*   **The Standard Intuition:** Use scattered, component-specific endpoints to handle user actions and return fragmented HTML.
*   **The Datastar Reality:** Having many small, specific endpoints for different UI components is an anti-pattern. Instead, use a **Single Long-Lived SSE Endpoint** that "owns" the user's view of the app.
*   **The Guideline:** Enforce strict CQRS (Command Query Responsibility Segregation). 
    *   **Commands (Actions):** Actions modify the database and return an empty `204 No Content`. Actions should *never* update the view directly via patch elements. If an action patches the DOM, that change will simply get overwritten by the next SSE push. (Actions can, however, update signals for client-side validation).
    *   **Queries (The View):** The single SSE endpoint observes the database. When the database changes, it pushes a new view down the stream. Because `view = f(state)`, the connection itself remains perfectly **stateless**. No connection state needs to be maintained, meaning missed events, server deploys, or proxy reconnects will never lead to lost UI state.

### 3. The Scaling Secret: Time-Based Batching Over Fine-Grained Diffs
*   **The Standard Intuition:** When a user clicks a button, calculate exactly who needs to see that change, and send a targeted, fine-grained event only to those specific users to save bandwidth.
*   **The Datastar Reality:** Tracking user-specific state and figuring out "who needs what" is pure overhead. It creates a massive CPU bottleneck on the server.
*   **The Guideline:** **Calculate Once, Broadcast Everywhere.** Decouple user inputs from server renders. Instead of responding individually to every user action, queue up all incoming actions and commit them to the database. Then, use a time-based loop (e.g., every 100ms) to re-render the view and push the exact same HTML morph to *all* connected users. 
    *   *Why this works:* Re-rendering on *any* database change might sound scary, but it naturally handles back-pressure. If you have a shared view, 50%+ of your users might need an update anyway. By throttling updates to a resolution window (e.g., 100ms max frequency), you strictly cap the maximum work the server does, regardless of how many users are clicking at once.

### 4. Unintuitive Operational Outcomes
Because of this architecture, many traditional backend complexities simply vanish:

*   **Multiplayer requires zero code:** You don't need a heavy frontend framework to sync client state. Because the application is just returning an HTML view of the global server state, everyone gets the same updates simultaneously via SSE. The app is naturally collaborative by default.
*   **Rate limiting can actually hurt performance:** Standard wisdom says you must implement complex per-IP rate limiting to survive traffic spikes. But tracking rate limits in-memory can cause Out-Of-Memory (OOM) crashes under extreme load. By leaning into global batching, the core read/write loop becomes so fast and dumb that it is often safer to simply absorb the traffic (e.g., processing 40,000+ writes a second).
*   **The database *is* the cache (Skip Redis):** Don't build an in-memory caching layer until you absolutely have to. By tweaking SQLite (e.g., increasing memory pages), it acts almost entirely as an in-memory database while retaining persistence. Datastar applications have successfully saved *user scroll events* directly into SQLite in real-time.
*   **High concurrency does not require "fast" backend languages:** You do not need highly optimized, low-level backends to handle massive traffic. Because the heavy lifting is offloaded to SQLite, event batching, and standard HTTP streaming compression, a basic script in a "slow" dynamic language on a $5 VPS can easily survive Hacker News front-page traffic for a global multiplayer application.

## Routing & DOM Morphing

Building with Datastar requires a fundamental shift in how you think about routing, navigation, and DOM manipulation. Standard Single Page Application (SPA) heuristics—such as highly granular REST endpoints, complex client-side virtual routers, and surgical DOM updates—are actually anti-patterns here. 

### The Flat Router and the Death of REST
In traditional SPA or even basic HTMX development, developers often create an explosion of granular endpoints to fetch specific JSON payloads or localized HTML fragments. Datastar drastically reduces back-end routing complexity, often **deleting 50% or more of your routing table.**

* **Flat Routing Maps over Radix Trees:** Treat your router as a simple map. Avoid path parameters (e.g., `/users/:id`). Path parameters force you into arbitrary hierarchies and "place-oriented programming" that often turn out to be wrong as the app evolves. By removing them and using query or body parameters instead, your routing becomes a simple, flat map that performs exceptionally well.
* **Default to One Route Per Page:** Write applications like the old days of simple full-page flows. Render the whole view (`v = f(state)`) on the server, and rely on Datastar’s SSE connection to handle the interactive, in-page updates.

### The Initial Shim (Optimizing the First Render)
Rather than executing a heavy server render on the initial `GET` request (requiring two render paths: one for the initial page, one for subsequent updates), Datastar encourages rendering a lightweight "shim" or shell. 
1. The server returns a static HTML shell (which can be generated and compressed once).
2. The page connects to the Datastar SSE updates endpoint.
3. The server populates the dynamic content into the shell.

This offers significant advantages: dynamic content is only rendered and processed if the user actually has JavaScript and first-party cookies enabled. The server does less heavy lifting for link-preview crawlers and bots, saving expensive compute strictly for real users.

### Navigation vs. Morphing (Avoiding "Magic" Footguns)
To make applications feel fast, SPA developers have been trained to intercept all link clicks, prevent full page reloads, and swap out the URL and page contents using JavaScript (like Hotwire Turbo or React Router). 

**Datastar’s stance is the opposite:** Simulating full page transitions on the client creates "magical" footguns that are notoriously difficult to debug and manage. Embrace traditional HATEOAS. If the user is fundamentally navigating to a new page, **just do a full page reload.** Reserve Datastar’s SSE fragment morphing strictly for ephemeral, in-page state changes—like toggling buttons, updating live data, or submitting forms.

### The "Immediate-Mode Game Engine" Approach to the DOM
The standard web developer intuition assumes that overwriting massive chunks of the DOM (like a 2,500-cell data grid) repeatedly will freeze the browser and ruin performance. As a result, developers spend immense effort writing logic to target and update single nodes.

With Datastar, you should treat the web page like an **immediate-mode game engine.**
* **Fat Morphs:** You can achieve buttery-smooth performance by re-rendering the *entire* page state on the server every time the state changes. The server sends the raw, updated HTML fragment down the SSE stream. Datastar utilizes Idiomorph under the hood to rapidly diff and merge it against the existing DOM, updating only the exact elements that changed. 
* **Updating Disjointed UI is Cheap:** Updating multiple, unrelated parts of a page normally requires complex global state management (like Redux). With Datastar, it is trivial. If a user clicks a button, the server can effortlessly push an update to that button, push a toast notification to the top of the screen, and update a cart counter in the header, all in a single SSE stream. 
* **Trust the Morph:** Stop manually hunting for DOM elements. It is often much simpler (and perfectly performant) to replace an entire list over the wire rather than writing fragile edge-case logic to update a single item within it.

### Let the Framework Handle the UI Edge Cases
Do not attempt to write vanilla JS to handle DOM swaps (e.g., `selector.outerHTML = await fetch()`). While it sounds simple, it is fundamentally broken for production apps.

Despite being 40% smaller than HTMX, Datastar's patching engine automatically handles complex UI edge cases that usually require bloated JavaScript shims:
* **Cursor and Focus State:** Datastar maintains text and cursor selection seamlessly even when an element is morphing out from underneath the user.
* **Visibility Pruning:** With the default `openWhenHidden: false`, Datastar closes the SSE stream when the tab is hidden and reopens it when it becomes visible again. This prevents the browser and server from doing idle work while the user is away, and instantly fetches the most current state upon their return. 
* **Stream Retries:** Note that reopening a successfully started stream after it ends for other reasons requires explicitly setting `retry: 'always'` (it is not provided by the default retry policy). 

### Exceptional Integration Seams (Escaping the Morph)
When Datastar needs to interact with opaque subtrees owned by the client (like a complex 3rd-party JS charting library) or attributes narrowly owned by the browser, you must define integration seams. 

* Use **`data-ignore-morph`** on elements to tell Datastar to leave an entire subtree alone. Emit this consistently in your server-rendered markup.
* Use **`data-preserve-attr`** to protect specific attributes from being overwritten by the server. *Warning:* Preserving an entire `class` attribute is exceptionally sharp, as it prevents the server from changing *any* class on that element in the future.
* **Scoped MutationObservers:** If state must be derived from a browser-only API, a scoped `MutationObserver` or private JS property is appropriate. However, treat this as an integration seam, not application state. Because Idiomorph often edits attributes in-place on surviving nodes rather than replacing the node entirely, an observer that only watches for child-list mutations will likely miss changes. Ensure these observers are idempotent and account for attribute mutations.

## Embracing the Native Web Platform

Modern Single Page Application (SPA) frameworks have spent the last decade abstracting the browser away behind virtual DOMs and synthetic event wrappers. Because of this, developers have developed a blind spot for how powerful the native web platform actually is. Datastar aggressively strips away these JavaScript abstractions to rely on the browser’s underlying C++ engine. 

To succeed with Datastar, you must unlearn SPA habits and lean into native HTML, CSS, and DOM APIs.

### 1. Leverage Native Event Bubbling for Scale
* **The SPA Intuition:** If you have a grid of 20,000 checkboxes, you must attach an `onClick` component lifecycle handler to every single one.
* **The Datastar Reality:** Attaching thousands of listeners in JavaScript causes severe memory and performance overhead. Browsers natively handle event bubbling in highly optimized C++.
* **The Guideline:** Never attach thousands of identical listeners. Instead, attach a *single* `data-on:click` listener to the parent container. Place standard HTML `data-id` or `data-action` attributes on the child elements. When a child is clicked, the event natively bubbles up to the parent, which reads the attributes and triggers the server request. *(Pro-tip: Use CSS `pointer-events: none` on child elements you want the browser to ignore during the click phase).*

### 2. Replace "Optimistic UI" with CSS and Native Timings
* **The SPA Intuition:** Because network latency exists, you must write complex JavaScript to instantly update the UI state locally (Optimistic UI), and write even more complex JS to roll that state back if the server request fails.
* **The Datastar Reality:** Tricking the human brain is significantly cheaper than managing distributed state synchronization. You do not need JavaScript to make an app feel instantaneous.
* **The Guideline:** Keep the server as the sole source of truth, and bridge the latency gap with native CSS and DOM timings:
    * **Use `pointerdown` over `click`:** Bind your events to `data-on:pointerdown` (or `mousedown`). This fires the moment the mouse button is depressed, sending the server request fractions of a second faster than waiting for the full `click` event to complete.
    * **CSS "Pop" Animations:** On `pointerdown`, trigger a 200–300ms CSS transition. By the time the visual animation finishes, the server round-trip has completed, and Datastar seamlessly morphs the true server state into the DOM. 

### 3. Reject JavaScript Abstractions for UI Polish
* **The SPA Intuition:** The framework should provide declarative JavaScript wrappers for animations, window resizing, and scrolling. 
* **The Datastar Reality:** Adding JS abstractions for UI polish introduces unnecessary footprint, support burdens, and "footguns." Datastar refuses to bloat its ~10kb core with features the browser already has built-in.
* **The Guideline:** Delegate UI polish to native web features.
    * **Animations:** Use CSS animations, not JavaScript attributes. If you require highly complex choreographies, pull in a tiny, dedicated vanilla library (like `anime.js`) rather than expecting framework integration.
    * **Window Events:** Do not use JS resize observers; use Datastar's standard syntax to listen directly to native window events (e.g., `data-on:window...`).
    * **Scrolling:** Trigger native DOM methods directly via hooks, such as `data-on:load="el.scrollIntoView()"`.

### 4. Embrace "Ugly" HTML Attributes (Locality of Behavior)
* **The SPA Intuition:** Frontend logic belongs in separate JavaScript files, hooks, or complex component lifecycles. HTML should just be a dumb template. 
* **The Datastar Reality:** Standard SPA developers often look at Datastar and complain that it looks "crazy," "ugly," or like a "mish-mash of ad-hoc DSLs." This completely misses the point. Embedding logic directly into the HTML is the core feature, not a bug.
* **The Guideline:** Maximize the declarativeness of your HTML. Keep your frontend logic minimal and define it completely within spec-compliant `data-*` attributes. Using Datastar’s expression DSL (e.g., `<input data-on:input__debounce.200ms="@get('/search')" />`) eliminates context-switching, removes the need for separate JS files, and keeps the behavior of the element perfectly localized to the element itself.

## Signals & Client-Side State

Standard hypermedia frameworks (like HTMX) rely entirely on DOM-swapping for state changes. While excellent for server state, this often creates a frustrating client experience where out-of-bounds (OOB) HTML updates accidentally wipe out ephemeral client state—like the text a user is currently typing into an input field, or the open/closed state of a local dropdown. 

Datastar incorporates modern frontend paradigms into its hypermedia approach using **Signals**. However, developers coming from React, Vue, or Svelte must completely unlearn how they use reactivity. 

### Signals are Strictly for Ephemeral UI State
**The Counter-Intuitive Claim:** You can have rich, persistent client-side interactivity without a heavy JavaScript framework, but you must resist the urge to store your data models in the client.

In standard SPAs, the instinct is to pull backend data models (like deep JSON objects) directly into frontend reactive state so the UI can bind to them. **Do not do this in Datastar.** 
Because of how Datastar parses signals and translates HTML attributes to JavaScript (converting `kebab-case` to `camelCase` and dealing with modifiers), dumping complex backend data structures into frontend signals creates a mess. For example, if you have complex backend data with unconventional keys (e.g., Kubernetes `map[string]string` labels formatted like `example.com/label-key`), attempting to store them directly in Datastar signals will cause parsing headaches.

Keep heavy domain data strictly on the server. Signals should *only* be used for ephemeral client-side facts, such as:
* The current value of a text input (drafts)
* Whether a popover or menu is visible
* The current CSRF token
* Client-side input validation errors

### Preserving State: `__ifmissing` and Local Signals
Because signals represent ephemeral state, their initialization and synchronization require specific modifiers to play nicely with server-rendered HTML. 

**Standard Signals (`__ifmissing`)**
Signals are typically initialized by elements in the DOM and can be changed via client expressions or from the backend via a `patch-signals` action. To prevent a server-rendered DOM morph from accidentally resetting a user's active UI state, signals attached to elements should almost always be declared using the `__ifmissing` modifier (e.g., `data-signals__ifmissing="{...}"`). This tells Datastar to respect the existing client-side value if it's already active.

**View-Only Local Signals (`_`)**
Sometimes you have a signal that is strictly "view-only"—meaning it is only ever changed by the server pushing updates, and the client never needs to mutate it. These should **not** be declared with `__ifmissing`. Instead, they should be made "local" by prefixing their key with an underscore (e.g., `_mySignal`). This prevents the client from needlessly sending that data back up to the server on every request.

### Decide Who Owns DOM State Before Adding a Morph Boundary
Under the hood, Datastar uses Idiomorph to reconcile server-rendered attributes on surviving elements while inserting and removing nodes. This means if you imperatively add a class or attribute in the browser (via standard JS), it will disappear on the next morph if it is absent from the server's HTML.

Developers often misinterpret this as a flaw with morphing and try to create "ignored morph boundaries" to protect their UI. Usually, this is actually an **ownership conflict**. Before reaching for an escape hatch, choose who owns the state:

1. **Domain state is server-owned.** Change it through a server action and render it in the next current-state view. Do not maintain a competing browser-only class or attribute for the same fact.
2. **Ordinary ephemeral UI state is signal-owned.** Use signals for drafts, popovers, and transient visual states. Initialize them with `__ifmissing` and bind the DOM representation declaratively.
3. **Specialized imperative behavior is component-owned.** When faced with complex client requirements (like interactive canvas animations, data grids, or third-party mapping widgets) that HTML and Datastar expressions cannot reasonably express, do not try to force them into signals. Instead, wrap them in a tiny, vanilla Web Component (e.g., the complex "slick Star space animation" on the Datastar homepage is just a 1kb Web Component). Drive its public surface with Datastar signals and host attributes where practical.
4. **A morph boundary is exceptional.** Declare an ignored morph boundary *only* when morphing would directly violate the chosen ownership or corrupt an external component's internal lifecycle.

Crucially: A Web Component is not automatically an ignored island. Datastar can often continue safely morphing a custom element's host attributes while the component securely owns its internals. Conversely, ordinary signal-driven UI does not need a Web Component at all.

## Operational Behaviors & Security

Operating an immediate-mode, server-driven architecture like Hyperlith requires unlearning several deeply ingrained SPA habits. Because the UI is bound to a persistent stream of truth from the server, everything from how you read network profiles to how you handle offline users changes fundamentally.

### Network DevTools Will Deceive You (The "Infinite Download" Illusion)
* **The Standard Intuition:** If you open the browser’s Network tab and see the page size growing to 20MB+, your frontend is bloated and performance will suffer.
* **The Datastar Reality:** Your initial load is actually microscopic—often just a ~12kb bundle containing the Datastar library, initial HTML, and CSS. The massive megabyte count in the Network tab is simply the browser tallying the continuous stream of compressed Server-Sent Events (SSE) over the lifecycle of the session.
* **Guideline:** Do not panic at cumulative network sizes. Evaluate performance based on initial time-to-interactive and rendering smoothness, not the running byte total of an open SSE stream.

### Hot-Reloading is Global, Not Just Local DX
* **The Standard Intuition:** Hot-reloading (HMR) is a local developer experience feature for updating frontend components.
* **The Datastar Reality:** Because the server is constantly streaming UI state, modifying server logic (like HTML, CSS, or backend rules via a REPL) instantly pushes those structural changes to *all* currently connected clients over the SSE stream. 
* **Guideline:** Server-driven streaming allows for unprecedented live-updates in production environments. You can push structural UI updates without requiring users to refresh their browsers or restart the server.

### Connection Limits and Visibility-Based Pruning
* **The Standard Intuition:** Maintaining a persistent connection for every single user will exhaust connection pools (especially given HTTP/1.1 limits of ~6 concurrent connections per browser) and drain mobile batteries.
* **The Datastar Reality:** Datastar intelligently hooks into the browser's native Page Visibility API. If a user switches tabs or minimizes the window, Datastar can prune the connection to aggressively save client battery life and server resources.
* **Guideline:** Rely on the Visibility API to manage idle users. Furthermore, you should strongly prefer HTTP/2 (which negotiates around 100 concurrent connections by default) to entirely bypass legacy HTTP/1.1 connection limits.

### Network Resilience: Native Sockets vs. SPA Timeouts
* **The Standard Intuition:** SPAs (React/Vue) handle spotty 2G/3G networks better because the client logic is already loaded, allowing JS to elegantly manage loading states and retries.
* **The Datastar Reality:** SPAs on 2G/3G often fail entirely because enterprising engineers usually invent their own arbitrary JavaScript timeouts that make no sense when dealing with a trickle of bytes-per-second. Standard HTML requests and SSE streams rely on native browser socket behavior. The browser intrinsically knows if it is still receiving bytes (even slowly) and won't prematurely kill the request.
* **Guideline:** Trust the platform. HTML rendering and SSE with a durable retry policy (e.g., `retry: 'always'`) often prove far more resilient in ultra-low bandwidth scenarios than custom SPA timeout logic.

### The Offline Paradox: No Optimistic UI, but PWAs Still Work
* **The Standard Intuition:** If an app requires constant server contact to render UI, it cannot function as an offline Progressive Web App (PWA).
* **The Datastar Reality:** Because there is no client-side state, a true "offline mode"—where a user performs complex optimistic UI mutations (like adding items to a cart while in airplane mode) and syncs later—is structurally impossible. **However**, offline read-only support is entirely possible.
* **Guideline:** You can achieve offline PWA support by shifting your caching strategy to a Service Worker. Simply cache the backend-generated HTML. The Datastar library running in the main thread doesn't care if the HTML containing its declarative attributes came from a live network request, an edge worker, or a service worker cache. 

### Security & Session Posture
Because Hyperlith heavily limits the responsibilities of the client, the security model is significantly simplified, with one notable exception regarding Content Security Policies (CSP).

* **No CORS:** By serving all assets, API endpoints, and SSE streams from the same origin, we completely avoid the need for Cross-Origin Resource Sharing (CORS). This eliminates CORS preflight round-trips, inherently reducing latency.
* **Simple, Secure Sessions:** Hyperlith uses a simple, unguessable random UID for managing sessions via standard HTTP-only cookies. This UID is used server-side to look up authentication and permission data in the database.
* **CSRF Protection:** Hyperlith relies on the standard Double Submit Cookie pattern to mitigate Cross-Site Request Forgery.
* **The `unsafe-eval` Trade-off:** 
    * **The Standard Intuition:** Modern web apps should strictly ban `eval()` in their CSP to prevent cross-site scripting (XSS).
    * **The Datastar Reality:** To maintain its tiny footprint and high performance, Datastar evaluates expressions using Immediately Invoked Function Expressions (IIFEs). This strictly requires `unsafe-eval` to be enabled in your CSP for scripts. (Unlike tools such as HTMX, which allow you to disable eval-reliant features, Datastar requires it).
    * **Guideline:** Be aware of this compliance requirement. If your corporate security policies rigidly forbid `unsafe-eval` under any circumstances, you will face structural friction adopting Datastar out-of-the-box. Ensure your server-side HTML sanitization is robust to compensate for this CSP relaxation.
