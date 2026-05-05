# Patterns: AI Agent Infrastructure in Clojure

## The Message Loop Pattern
Always return the full message history from the agent loop to allow the caller to decide on persistence and context management.

```clojure
(defn run-loop [messages]
  (loop [msgs messages]
    (let [resp (call-model msgs)]
      (if (:tool_calls resp)
        (recur (conj msgs ...))
        msgs))))
```

## The Tool Registry Pattern
Use a central atom to store tool definitions and handlers, allowing for dynamic registration from plugins or MCP servers.

```clojure
(def registry (atom {}))
(defn register! [name handler schema]
  (swap! registry assoc name {:handler handler :schema schema}))
```

## The Background Review Pattern
Launch an independent agent instance in a `future` after the main conversation ends. Ensure `nudge-threshold` is set to 0 to avoid infinite recursion.

```clojure
(defn review! [messages]
  (future
    (binding [*nudge-threshold* 0]
      (agent/run "Review..." messages))))
```

## The Gateway Adapter Pattern
Decouple the agent logic from platform-specific APIs. Use a uniform `MessageEvent` structure.

```clojure
(defprotocol IPlatform
  (send-message [this text])
  (get-updates [this]))
```
