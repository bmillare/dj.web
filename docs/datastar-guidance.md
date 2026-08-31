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

### Batching

Batching pairs really well with CQRS as you have a resolution window, this defines the maximum frequency the view can update, or in other terms the granularity/resolution of the view. Batching can generally be used to improve throughput by batching changes.

### Time-Based Batching Over Event-Based Responding
**The Unintuitive Claim:** The server does not need to respond individually to every user action to achieve real-time reactivity at scale.
**The Insight (from andersmurphy):**
At massive scales, the server remains completely in control of the data flow by batching updates.
*   Instead of rendering on every click, the server batches all changes and pushes a new view at a set interval (e.g., every 100ms).
*   **Scale multiplier:** If multiple users are looking at the same view (like a Game of Life demo), the server only performs **one render per 100ms**, and pushes that same render to *all* concurrent users.
*   **Guideline:** Decouple user inputs from server renders. Use loops that broadcast state changes at fixed intervals to naturally handle back-pressure and prevent server overloads under heavy traffic.

---

> This is a particularly novel point.

### Tracking User-Specific State is "Pure Overhead" Under Heavy Load
* **The Standard Intuition:** Only push data to the specific users who are looking at the exact widget that changed, to save bandwidth.
* **The Datastar Insight (via Anders Murphy):** When building realtime/multiplayer systems, tracking *who* needs *what* fine-grained update becomes a massive server bottleneck. If you have a shared widget, 50%+ of your users might need an update anyway.
* **Guideline:** Make updates **coarse-grained and homogeneous**. It is often vastly more performant to simply update all connected users on a set interval (e.g., every X milliseconds) whenever something changes. This allows you to easily throttle push rates and batch changes, preventing the server from melting under high concurrent load.

### Calculate Once, Broadcast Everywhere (Global over User-Specific Compute)
* **The Standard Intuition:** Applications must evaluate expensive metrics based on individual user filters and views.
* **The Datastar Insight:** Expensive server queries should be shared or globally cached wherever possible. For example, in Anders Murphy's multiplayer "Game of Life" demo (which runs on a $5 VPS and survived the HN front page), each frame is rendered and calculated exactly **once**, regardless of the number of users viewing it.

### Work sharing (caching)

Work sharing is the term I'm using for sharing renders between connected users. This can be useful when a lot of connected users share the same view. For example a leader board, game board, presence indicator etc. It ensures the work (eg: query and html generation) for that view is only done once regardless of the number of connected users.

The simplest way to do this is to recalculate and cache values after a batch has been run.

---

### Rate Limiting Can Actually Hurt Performance
* **The Standard View:** If script kiddies hammer your server, you must implement complex per-IP rate limiting to save your app.
* **The Datastar Insight:** Tracking rate limits in memory can actually cause Out-Of-Memory (OOM) crashes under extreme load. By leaning into batching (Insight #3), the server is so fast it can process 40,000+ writes a second.
* **Guideline:** Instead of building complex infrastructure to block traffic, make your core read/write loop so fast and dumb that you can just absorb the traffic.

---

### Batching + Global Updates > Fine-Grained Diffs
* **The Standard View:** When a user clicks a button, calculate exactly who needs to see that change, and send a targeted, fine-grained update to only those users.
* **The Datastar Insight:** Figuring out "who needs what" creates massive CPU overhead on the server. Instead, use a simple CQRS (Command Query Responsibility Segregation) pattern. Queue up all incoming actions, commit them in a single database transaction every 100 milliseconds, and simply push the new resulting view to *all connected users* at once.
* **Guideline:** For multiplayer or highly interactive apps, batch server updates on a fixed loop (e.g., every 100ms) rather than firing updates for every individual interaction. Make queries blazingly fast and just re-render.

---


### SSE (Server-Sent Events) > WebSockets
*   **The standard intuition:** WebSockets are the ultimate tool for real-time, bi-directional web applications.
*   **The Datastar reality:** Operationally, WebSockets are a "nightmare" at scale. They suffer from blocked ports, load-balancing difficulties, lack of multiplexing (which can lead to accidental DDoS issues), high mobile battery drain, and no built-in compression.
*   **Guideline:** Use SSE. Because SSE operates over standard HTTP, it inherits multiplexing, header support, built-in compression, and HTTP/2 & HTTP/3 benefits. A stream that must reopen after a `200 OK` response later ends (for example during a deploy, proxy timeout, or render failure) requires an explicit client policy such as `@get('/updates', {retry: 'always', retryMaxCount: 1000})`; the default `retry: 'auto'` retries a failed fetch but does not reopen a successfully started stream after a clean end.

### Server-Sent Events (SSE) > WebSockets for Real-Time State
When developers think of real-time server-to-client communication, WebSockets are almost always the default choice.
* **The Unintuitive Claim:** Datastar relies on Server-Sent Events (SSE) rather than WebSockets. Advocates point out that SSE is vastly simpler, aligns better with standard HTTP/web standards, and integrates natively with backend SDKs to push HTML fragments and signal updates seamlessly.
* **Guideline:** Use SSE to stream UI updates from the server to the client. It removes the overhead and complexity of managing bidirectional WebSocket connections.

---

### Stateless

The only way for actions to affect the view returned by the `render-fn` running in a connection is via the database. This ensures CQRS. It means there is no connection state that needs to be persisted or maintained, so missed events and shutdowns or deploys will not lead to lost state. Even when you are running in a single process there is no way for an action (command) to communicate with or affect a view render (query) without going through the database.

### Real-time Can Be Truly Stateless
*   **The standard intuition:** Server-rendered real-time apps (like Phoenix LiveView) require a heavy, stateful, persistent connection to track diffs.
*   **The Datastar reality:** Datastar requires no connection state, no server-side diffing, and no WebSockets. The client does not even need to communicate with the exact same server node on subsequent requests.
*   **Guideline:** Design your backend statelessly. Because the server is just processing a request and streaming HTML back over HTTP, load balancing and scaling become vastly simpler than traditional real-time frameworks.

### Real-Time Complexity Requires Almost No Client Code
**The Standard View:** Building highly interactive, real-time applications (like a multiplayer application) requires heavy client-side state management (Redux, Zustand), WebSockets, and a massive frontend framework.
**The Datastar Insight (Anders Murphy):**
* You don't need a heavy frontend to achieve high-performance real-time synchronization. Anders notes that with Datastar, you can "build a multiplayer spreadsheet performantly and realtime in a few hundred lines of code." By allowing the backend to control the state and streaming updates via SSE, you bypass the need to sync complex client-side state entirely.

---

### "Multiplayer" Synchronization Requires Zero Code
*   **The standard intuition:** Making an app "multiplayer" or collaborative requires complex client-side state syncing and conflict resolution.
*   **The Datastar reality:** Because the application is just returning an HTML view of the global server state, everyone gets the same updates simultaneously.
*   **Guideline:** Don't write syncing code. If you want everyone to see the same collaborative view, the server just renders the exact same global state to all connected clients. The app is naturally multiplayer by default.

### Real-Time "Multiplayer" Apps Become Trivial
* **The Unintuitive Claim:** Building collaborative, real-time apps does not require WebSockets or heavy client-side libraries.
* **The Insight:** **andersmurphy** points out that because Datastar natively utilizes SSE and morphing, building real-time multiplayer over large datasets becomes "trivial out of the box." When state changes on the server for one user, the server can effortlessly push the morph down to all other connected clients over SSE.
* **Guideline:** Use SSE as your default transport for collaborative features. It provides one-way real-time reactivity from server to client without the overhead of managing bidirectional WebSockets.

---

### Why have single render function per page?

By having a single render function per page you can simplify the reasoning about your app to `view = f(state)`. You can then reason about your pushed updates as a continuous signal rather than discrete event stream. The benefit of this is you don't have to handle missed events, disconnects and reconnects. When the state changes on the server you push down the latest view, not the delta between views. On the client idiomorph can translate that into fine grained dom updates.

### The "Single Long-Lived SSE Endpoint" Architecture
**The Unintuitive Claim:** Having many small, specific endpoints for different UI components is an anti-pattern.
**The Insight (from ndyg & throwaway7783):**
While frameworks like Turbo or standard HTMX often rely on scattered endpoints returning HTML fragments, Datastar thrives on a single Server-Sent Events (SSE) endpoint.
*   This single endpoint "owns" the user's view of the app. It streams updates to their field of view as appropriate.
*   This completely removes the need to wrangle complex template logic (like passing "isOob" flags to determine if a component is Out-Of-Band or not).
*   **Guideline:** Design your backend to maintain a single SSE stream per user session that acts as the ultimate source of truth for what the client should see.

---


### Actions should not update the view themselves directly

Actions should not update the view via patch elements. This is because the changes they make would get overwritten on the next `render-fn` that pushes a new view down the updates SSE connection. However, they can still be used to update signals as those won't be changed by elements patch. This allows you to do things like validation on the server.

### CQRS

- Actions modify the database and return a 204.
- Render functions re-render when the database changes and send an update down the updates SSE connection.

---

### Hypermedia is Fully Capable of Real-Time and Collaborative Apps
Many developers assume that HTMX/Datastar-style frameworks are only good for basic CRUD apps and that you need heavy JS frameworks for real-time collaboration.
* **The Unintuitive Claim:** Anders Murphy routinely proves this wrong by building demos that handle real-time/collaborative applications purely using hypermedia.
* **Guideline:** Don't default to a heavy JS client just because an app requires real-time updates.

---

### High Concurrency Doesn't Require High-Performance Backend Languages
Standard web dev assumes that highly interactive, multiplayer, or realtime apps (like Game of Life or collaborative checkboxes) require heavy, highly optimized backends and complex client-side state managers.
*   **The Unintuitive Insight:** You can handle front-page Hacker News traffic for global multiplayer applications on a $5 VPS using a "slow" dynamic language (Anders uses Clojure).
*   **The Guideline:** Rely on foundational, highly-optimized tools rather than application-level code. By using SQLite, basic event batching, and streaming compression, you offload the hard work. The backend stays dramatically simpler because all it has to do is broadcast HTML over SSE. Follow the compression defaults and escalation order above rather than designing a fine-grained protocol pre-emptively.

---

### The Database *Is* the Cache (Skip Redis)
* **The Standard View:** Hitting the disk for every user action will crash the server. You need an in-memory cache (Redis) and complex syncing logic.
* **The Datastar Insight:** SQLite, when configured correctly (increasing memory pages), acts almost entirely as an in-memory database while retaining persistence. Anders was able to save *user scroll events* directly into SQLite in real-time on a $5 server.
* **Guideline:** Don't build a caching layer until you absolutely have to. Write directly to SQLite. Let the database's native page management handle memory.

---

### Why re-render on any database change?

When your events are not homogeneous, you can't miss events, so you cannot throttle your events without losing data.

But, wait! Won't that mean every change will cause all users to re-render? Yes, but at a maximum rate determined by the throttle. This, might sound scary at first but in practice:

- The more shared views the users have the more likely most of the connected users will have to re-render when a change happen.

- The more events that are happening the more likely most users will have to re-render.

This means you actually end up doing more work with a non-homogeneous event system under heavy load than with this simple homogeneous event system that's throttled (especially if there's any sort of common/shared view between users).

## Routing & DOM Morphing
### Client-Side DOM Morphing is Lightning Fast
*   **The standard intuition:** Overwriting massive chunks of the DOM (like a 2,500-cell grid) repeatedly will freeze the browser and ruin performance.
*   **The Datastar reality:** Datastar utilizes a highly optimized morphing algorithm under the hood. The server sends the raw, updated HTML fragment, and Datastar rapidly diffs and merges it against the existing DOM, only updating the exact elements that changed.
*   **Guideline:** Trust the morphing algorithm. Your standard CRUD apps (and even complex grid-based games) will perform flawlessly without the overhead of a Virtual DOM.

---

### Rendering an initial shim

Rather than returning the whole page on initial render and having two render paths, one for initial render and one for subsequent rendering a shell is rendered and then populated when the page connects to the updates endpoint for that page. This has a few advantages:

- The page will only render dynamic content if the user has javascript and first party cookies enabled.

- The initial shell page can generated and compressed once.

- The server only does more work for actual users and less work for link preview crawlers and other bots (that don't support javascript or cookies).

---

### The "Immediate-Mode Game Engine" Approach to the DOM
* **The Unintuitive Claim:** You can achieve "buttery smooth" performance by re-rendering the *entire page* on the server every time the state changes, rather than calculating granular, targeted updates.
* **The Insight:** Instead of creating dozens of micro-routes or targeted endpoints for specific UI components, developers are treating the web page like an immediate-mode game engine. You re-render the whole page state on the server and push it down. Datastar's "Fat Morphs" handle merging the changes smoothly into the existing DOM without flashing or glitching.
* **Guideline:** Stop manually hunting for DOM elements to update. Consolidate your routing. Render the whole view (`v = f(state)`) on the server and let Datastar’s morphing do the heavy lifting.

---

### Deleting 50% of Your Routing Table
* **The Unintuitive Claim:** Moving away from a REST/JSON API for your front-end drastically *reduces* back-end routing complexity.
* **The Insight:** In traditional SPA or even basic HTMX development, developers often create an explosion of endpoints to fetch specific HTML fragments or JSON payloads for localized state updates. Because Datastar allows you to efficiently push full-page morphs over SSE, **Aeolos** noted they were able to remove ~50% of their routing table.
* **Guideline:** Default to "one route per page." Write applications like the old days of simple full-page flows, and rely on Datastar’s SSE + morphs to prevent actual page reloads.

### Routing

Router is a simple map, this means path parameters are not supported use query parameters or body instead. I've found over time that path parameters force you to adopt an arbitrary hierarchy that is often wrong (and place oriented programming). Removing them avoids this and means routing can be simplified to a map and have better performance than a more traditional adaptive radix tree router.

### Navigation vs. Morphing (Avoiding "Magic" Footguns)
**The Standard View:** To make an app feel fast, you should intercept all link clicks, prevent full page reloads, and swap out the URL and page contents using JavaScript (like Hotwire Turbo or standard SPA routers).
**The Datastar Insight (nchmy):**
* Simulating page transitions on the client creates "magical" footguns that are difficult to debug and manage.
* **The Guideline:** Embrace traditional HATEOAS. If you are changing the actual page, just do a full page reload. Reserve Datastar’s SSE fragment morphing specifically for **ephemeral, in-page state changes** (like toggling buttons, updating live data, or submitting a form).

### Exceptional integration seams

Use `data-ignore-morph` when the client or an external component owns an opaque
subtree, and emit it consistently in the server-rendered markup. Use
`data-preserve-attr` when the browser narrowly owns a named attribute. Preserving
an entire `class` attribute is especially sharp because it also prevents the
server from changing any class on that element.

A scoped `MutationObserver` or private JavaScript property can be appropriate
inside an owning component when state must be derived from a browser-only API.
Keep that mechanism private and idempotent; it is an integration seam, not an
application state model. In particular, Idiomorph may edit attributes on a
surviving node, so an observer that truly derives browser decoration cannot
assume that watching only child-list mutations is sufficient.

### Let the Framework Handle the UI Edge Cases
**The Unintuitive Claim:** Doing simple DOM swaps yourself in vanilla JS is fundamentally broken for production apps.
**The Insight (from sudodevnull):**
While one might think `selector.outerHTML = await fetch()` is all you need, doing it manually ignores massive edge cases.
*   Datastar handles complex UI state issues that usually require bloated JS shims, such as reconnecting on tab visibility changes and maintaining text/cursor selection when an element is swapped out from underneath the user. With the default `openWhenHidden: false`, Datastar closes the stream while hidden and reopens it when visible, avoiding idle work while returning to current state. Reopening a successfully started stream after it ends for other reasons requires `retry: 'always'`; it is not provided by the default retry policy.
*   Despite handling these edge cases, Datastar maintains a tiny footprint (40% smaller than HTMX).
*   **Guideline:** Rely on the framework for DOM patching. Don't write custom JS to manipulate the DOM, as you will likely break the graceful handling of these edge cases.

### Updating Disjointed UI Elements is Cheap
**The Unintuitive Claim:** Updating multiple, unrelated parts of a page requires complex global state management (like Redux).
**The Insight (from pragma_x & hunvreus):**
Because of how the SSE patching engine works, updating elements that are vastly separated in the DOM is trivial.
*   If a user clicks a button, the server can effortlessly push an update to that button, push a toast notification to the top of the screen, and update a cart counter in the header, all in one stream.
*   It is often much simpler (and perfectly performant) to replace an entire list rather than writing edge-case logic to update a single item within that list.
*   **Guideline:** Don't fear updating multiple separate elements or replacing entire blocks of HTML (like whole lists). The framework's patching engine and network compression make "fat morphs" highly efficient.

---


## Embracing the Native Web Platform
### Native Web Platform Tricks Pay Off
*   **The standard intuition:** Framework-specific event handling (like React's synthetic events) handles optimizations for you.
*   **The Datastar reality:** Spending too much time in modern SPAs makes developers forget native HTML tricks.
*   **Guideline:** Leverage native DOM behaviors like **event bubbling**. Instead of attaching an `on-click` listener to 2,500 individual elements, you can attach a single listener to the top-level container, drastically cutting down on client-side memory usage while keeping the payload identical.

### Native Browser Features > JS Framework Abstractions
Many frontend frameworks provide declarative JavaScript wrappers for animations, scrolling, or window resizing. The Datastar core actively rejects these to maintain a ~10kb footprint and avoid "footguns."
*   **The Unintuitive Insight:** You don't need a JS framework to animate things or listen to the window. Adding JS abstractions for these things introduces unnecessary support burdens and performance overhead.
*   **The Guideline:** Leverage CSS and native DOM APIs directly.
    *   *Animations:* You should be using native CSS for animations, not JS attributes.
    *   *Window Events:* Use Datastar's `data-on` to listen directly to window-level resize events natively.
    *   *Scrolling:* Simply replicate scroll behaviors natively with hooks like `data-on:load="el.scrollIntoView()"`.

### Native Browser Event Bubbling > Framework Event Listeners
* **The Standard View:** Frameworks should handle event binding. If you have a grid of 20,000 checkboxes, you attach an `onClick` handler to each component in your JS framework.
* **The Datastar Insight:** Attaching thousands of listeners in JS is slow. Browsers inherently support "event bubbling" natively in C++. You only need a *single* event listener on the parent container.
* **Guideline:** Use native HTML features. Put a single listener on a parent wrapper, and use `data-id` or `data-action` attributes on the children. When a child is clicked, the event bubbles up, the parent reads the data attributes, and Datastar sends it to the server. Use CSS `pointer-events: none` on things you don't want clicked.

---

### CSS Animations Replace Optimistic UI State
* **The Standard View:** Because of network latency, you must write JavaScript to instantly update the UI (optimistic UI) and then roll it back if the server fails.
* **The Datastar Insight:** You can trick the human brain using native CSS. When a user clicks, trigger a 200-300ms CSS "pop" animation on `mousedown`. By the time the animation finishes, the server round-trip has completed, and Datastar morphs the final state into place.
* **Guideline:** Use CSS animations for immediate user feedback. Let the server remain the single source of truth for the actual data state.

---

### Ugly HTML Attributes = Excellent Developer Ergonomics
Modern frameworks often abstract logic away into separate JS files or complex component lifecycles. Datastar puts logic directly into HTML attributes using a custom DSL (e.g., `<input data-on:input__debounce.200ms="@get('/examples/active_search/search')" />`).
* **The Unintuitive Claim:** While standard devs in the thread call this "crazy," "wrong," or a "mish-mash of different ad-hoc DSLs," the Datastar advocates argue this is the exact point of the framework. It keeps you within spec-compliant `data-*` attributes while maximizing the declarativeness of HTML.
* **Guideline:** Keep your frontend logic minimal and declarative inside the DOM elements. Use Datastar's expression DSL in standard HTML `data-*` attributes to handle events, debouncing, and server requests without writing separate client-side JavaScript

---

### Delegate UI Polish to Native Web Features
When discussing features that were moved to the paid "Pro" tier (like `data-animate`), the community reveals a guideline for keeping the framework lean.
* **The Insight:** You don't need the framework to do everything. User `hide_on_bush` points out that if you need animations, you can easily use native CSS (*"css animations go brrrr"*) or lightweight vanilla JS libraries (like `anime.js`). Datastar relies on a modular, extensible architecture rather than shipping a bloated core library.

---

### Use `data-on:pointerdown/mousedown` over  `data-on:click`

This is a small one but can make even the slowest of networks feel much snappier.

---

Misc from readme

## Signals & Client-Side State

### Signals are only for ephemeral client-side state

Signals should only be used for ephemeral client side state. Things like: the current value of a text input, whether a popover is visible, current csrf token, input validation errors. Signals can be controlled on the client via expressions, or from the backend via `patch-signals`. See **Decide Who Owns DOM State Before Adding a Morph Boundary** for how signals fit between server-owned domain state and specialized client components.

### Signals in elements should be declared __ifmissing

Because signals are only being used to represent ephemeral client state that means they can only be initialised by elements and they can only be changed via expressions on the client or from the server via `patch-signals` in an action. Signals in elements should be declared `__ifmissing` unless they are "view only".

### View only signals

View only signals, are signals that can only be changed by the server. These should not be declared `__ifmissing` instead they should be made "local" by starting their key with an `_` this prevents the client from sending them up to the server.

### Signals for Ephemeral Client State
* **The Unintuitive Claim:** You can have rich, persistent client-side interactivity without a heavy JavaScript framework.
* **The Insight:** A common pain point with standard hypermedia (like HTMX) is that out-of-bounds (OOB) HTML updates can accidentally wipe out ephemeral client state, like text a user is currently typing into an input field. **andersmurphy** highlights that Datastar's lightweight "signals" solve this edge case cleanly.
* **Guideline:** Use Datastar signals exclusively for ephemeral, strictly client-side UI states (like toggling a local menu or preserving text input during a morph) to avoid needing hidden form inputs or fighting the hypermedia architecture.

### Keep Signals Simple; Leave Complex Domain Data on the Server
**The Standard View:** You should pull your backend data models (like a deep JSON object) directly into frontend reactive state so the UI can bind to it.
**The Datastar Insight (pst):**
* Because of how Datastar parses signals and translates HTML attributes to JavaScript (converting `kebab-case` to `camelCase` and dealing with modifiers), dumping complex backend data structures into frontend signals can create a mess.
* **The Guideline:** If you have complex backend data with unconventional keys (e.g., Kubernetes `map[string]string` labels formatted like `example.com/label-key`), do not try to store them directly in Datastar signals. Keep the heavy domain data strictly on the backend, and only use signals for simple UI interactions (opening a dropdown, capturing a text input).

### Data Reactivity is Driven by "Signals"
Standard hypermedia frameworks (like HTMX) rely almost entirely on DOM-swapping for state changes.
* **The Insight:** Datastar incorporates modern frontend paradigms into its hypermedia approach. `andersmurphy` briefly mentions that application logic is managed via **"signals"**. While not elaborated on deeply in this text, this suggests that fast, client-side reactivity in Datastar is handled by a signals-based state model rather than pure server-roundtrips for every minor interaction.

---
(Brent comment: this is important for actual in practice applications)
### Complex UI (Animations/Grids) is Solved via Web Components + Datastar
When faced with complex client-side requirements (like data grids or interactive canvas animations), developers usually reach for React components.
* **The Unintuitive Claim:** You can bridge the gap using tiny, vanilla Web Components driven by Datastar signals. For example, the complex "slick Star space animation" on the Datastar homepage is just a *"basic 1kb web component driven by datastar attributes."*
* **Guideline:** If you need highly specialized client-side execution that HTML plus Datastar expressions cannot reasonably express, wrap it in a lightweight Web Component and use signals and host attributes to drive its public surface. Let Datastar keep morphing the host when that contract is safe; add an ignored-morph boundary only when the component truly owns an opaque subtree or lifecycle. Do not introduce a Web Component for ordinary signal-driven UI.

---

### Decide Who Owns DOM State Before Adding a Morph Boundary

Idiomorph reconciles server-rendered attributes on surviving elements as well as
inserting and removing nodes. A class or attribute added imperatively in the
browser can therefore disappear on the next morph when it is absent from the
server's HTML. That is usually an ownership conflict, not a reason to repair the
attribute after every patch.

Choose the owner before choosing a morphing escape hatch:

1. **Domain state is server-owned.** Change it through an action and render it in
   the next current-state view. Do not maintain a competing browser-only class
   or attribute for the same fact.
2. **Ordinary ephemeral UI state is signal-owned.** Use signals for drafts,
   popovers, transient visual states, and other browser-local facts. Initialize
   live signals with `data-signals__ifmissing` so a later morph does not reset
   them, and bind the DOM representation declaratively.
3. **Specialized imperative behavior is component-owned.** Put browser APIs or
   external widgets that HTML plus Datastar expressions cannot reasonably
   express behind a small Web Component or adapter. Drive its public surface
   with signals and host attributes where practical.
4. **A morph boundary is exceptional.** Declare one only when morphing would
   violate the chosen ownership or an external component's lifecycle.

A Web Component is not automatically an ignored island. Datastar can often
continue morphing the custom element's host attributes while the component owns
its internals. Conversely, ordinary signal-driven UI does not need a Web
Component.

---

## Operational Behaviors & Security

### No CORS

By hosting all assets on the same origin we avoid the need for CORS. This avoids additional server round trips and helps reduce latency.

### Cookie based sessions

Hyperlith uses a simple unguessable random uid for managing sessions. This should be used to look up further auth/permission information in the database.

### CSRF

Double submit cookie pattern is used for CSRF.

---

### Network DevTools Will Deceive You (The "Infinite Download" Illusion)
*   **The standard intuition:** If you open the network tab and see the page size growing to 20MB+, your frontend bundle is bloated and performance will suffer.
*   **The reality/claim:** With Datastar, the initial load is actually microscopic (e.g., a 12kb bundle containing Datastar, initial HTML, and CSS). The massive megabyte count in the network tab is just the browser tallying the continuous stream of compressed Server-Sent Events over time.
*   **Guideline:** Don't panic at cumulative network sizes. Measure your initial time-to-interactive and actual rendering performance, not the running total of the SSE stream.

---


### Hot-Reloading Applies to *All* Connected Users Instantly
*   **The standard intuition:** Hot-reloading is a local developer experience (DX) feature for the frontend.
*   **The reality/claim:** Because the server is constantly streaming the UI state, modifying the server logic (like HTML, CSS, or backend rules via a REPL) instantly pushes those structural changes to *all* currently connected clients over the SSE stream.
*   **Guideline:** Server-driven streaming architectures allow for unprecedented live-updates without requiring users to refresh their browsers or restart the server.

---

### Visibility-Based Pruning Mitigates Resource Limits
*   **The standard intuition:** Maintaining a persistent connection for every user will exhaust connection pools (especially on HTTP/1.1 limits of 6 per browser) and drain mobile batteries.
*   **The reality/claim:** Datastar intelligently hooks into the browser's Page Visibility API. If the user switches tabs or minimizes the window, Datastar can prune the connection.
*   **Guideline:** While you should strongly prefer HTTP/2 (which negotiates around 100 connections by default), you can rely on the visibility API to aggressively save client battery life and server resources when the app isn't actively being viewed.

---

### Spotty Connectivity is Fine, but "Offline Mode" is Impossible
*   **The standard intuition:** If an app requires constant server contact to render UI, it will be unusable on mobile devices with unreliable connections.
*   **The reality/claim:** SSE can handle spotty connections (like 3G) gracefully when the client uses a durable retry policy such as `retry: 'always'`. However, because there is no client-side state, a true "offline mode" (like saving items to an offline cart) is structurally impossible.
*   **Guideline:** Assess your app's true offline requirements before adopting Datastar. If you need optimistic UI updates during total network death, this architecture isn't a fit. If you just need it to survive subway tunnels and bad 3G, SSE handles it seamlessly.

### Network Reliability: Sockets vs. Custom JS Timeouts
**The Standard View:** SPAs (React/Vue) handle bad networks better because the client is loaded and you can elegantly manage loading states and retries via JavaScript.
**The Datastar Insight (withinboredom):**
* SPAs on 2G/3G often fail to load entirely because "enterprising engineers usually invent their own timeouts that make no sense when you are dealing with bytes-per-second."
* Standard HTML requests rely on native browser socket behavior, which intrinsically knows if it is still receiving bytes (even slowly) and won't prematurely kill the request. HTML rendering actually proves more resilient in ultra-low bandwidth scenarios.

### PWA and Offline Support Doesn't Require an SPA
A common critique is that server-driven HTML frameworks cannot work offline (unlike SPAs which can hold state in JS).
* **The Unintuitive Claim:** You can achieve offline support by simply caching the backend-generated HTML (which already contains the declarative Datastar attributes) using a Service Worker. The Datastar library running in the main thread doesn't care if the HTML came from the backend, an edge worker, or a service worker cache.
* **Guideline:** For offline capabilities, shift your caching strategy to the Service Worker level rather than duplicating state management in the browser.


---

### The Security Trade-off: `unsafe-eval` is Required
*   **The standard intuition:** Modern secure web apps should strictly ban `eval()` in their Content Security Policies (CSP) to prevent cross-site scripting (XSS).
*   **The reality/claim:** Datastar evaluates expressions using Immediately Invoked Function Expressions (IIFEs), which strictly requires `unsafe-eval` to be enabled in your CSP for scripts. (By contrast, HTMX allows you to disable eval-reliant features).
*   **Guideline:** Be aware of the security compliance required for your project. If your corporate security policies strictly forbid `unsafe-eval`, you will face friction using Datastar out-of-the-box.
