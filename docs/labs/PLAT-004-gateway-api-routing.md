# PLAT-004 — Expose Platform with Gateway API

## Objective

Expose the Booking Platform through a single HTTP entry point while keeping both application Services internal to the Kubernetes cluster.

Routing requirements:

```text
/       -> booking-frontend:8080
/api/*  -> booking-api:8080
```

The frontend and backend Services must remain:

```text
ClusterIP
```

No application `NodePort` or `LoadBalancer` should be created.

---

## Initial State

At the beginning of this task, both application tiers were operational inside the `booking-dev` namespace.

```text
booking-dev

booking-frontend
├── ClusterIP Service
├── frontend Pod #1
└── frontend Pod #2

booking-api
├── ClusterIP Service
├── API Pod #1
└── API Pod #2
```

The frontend could be reached internally using:

```text
http://booking-frontend:8080
```

The backend could be reached internally using:

```text
http://booking-api:8080
```

However, no shared external HTTP entry point existed.

---

# Problem

The Angular application performs API requests using relative URLs:

```text
/api/hotels
/api/info
```

During local development, Angular CLI used `proxy.conf.json` to forward these requests to Spring Boot.

The production frontend container runs Nginx instead of `ng serve`.

Its configuration contains an SPA fallback similar to:

```nginx
location / {
    try_files $uri $uri/ /index.html;
}
```

As a consequence:

```text
GET /api/hotels
        |
        v
booking-frontend
        |
        v
Nginx
        |
        v
index.html
```

A test confirmed this behaviour:

```bash
kubectl run curl \
  --image=curlimages/curl \
  --rm -it \
  --restart=Never \
  -n booking-dev \
  -- curl -i http://booking-frontend:8080/api/hotels
```

The response was:

```text
HTTP/1.1 200 OK
Content-Type: text/html
Server: nginx
```

Even though the HTTP status was `200`, the response was incorrect because the API endpoint returned the Angular HTML document instead of JSON.

This demonstrated that HTTP status alone is not sufficient to validate application routing.

---

# Target Architecture

Gateway API was introduced as the platform entry point.

```text
                         Client
                            |
                            v
                     Envoy Proxy
                            |
                            v
                     HTTPRoute
                    /        \
                   /          \
                 /api          /
                  |            |
                  v            v
           booking-api    booking-frontend
             Service         Service
            ClusterIP       ClusterIP
               |               |
          +----+----+      +----+----+
          |         |      |         |
        API #1    API #2 Front #1  Front #2
```

---

# Gateway API Components

The implementation uses three main Gateway API resources:

```text
GatewayClass
     |
     v
Gateway
     |
     v
HTTPRoute
```

Their responsibilities are different.

## GatewayClass

Defines which controller implements the Gateway.

```text
booking-gateway-class
          |
          v
Envoy Gateway Controller
```

## Gateway

Defines the network entry point and listeners.

```text
booking-gateway
      |
HTTP :8080
```

## HTTPRoute

Defines how incoming HTTP requests are mapped to Kubernetes Services.

```text
/api/*  -> booking-api
/*      -> booking-frontend
```

---

# GatewayClass

The following GatewayClass was created:

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: GatewayClass

metadata:
  name: booking-gateway-class

spec:
  controllerName: gateway.envoyproxy.io/gatewayclass-controller
```

`GatewayClass` is cluster-scoped and therefore does not belong to the `booking-dev` namespace.

Validation:

```bash
kubectl get gatewayclass
```

Result:

```text
NAME                    CONTROLLER                                      ACCEPTED
booking-gateway-class   gateway.envoyproxy.io/gatewayclass-controller   True
```

This confirms that Envoy Gateway recognizes and accepts the GatewayClass.

---

# Gateway

The application Gateway was created inside `booking-dev`.

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway

metadata:
  name: booking-gateway
  namespace: booking-dev

spec:
  gatewayClassName: booking-gateway-class

  listeners:
    - name: http
      protocol: HTTP
      port: 8080

      allowedRoutes:
        namespaces:
          from: Same
```

The listener accepts HTTPRoutes from the same namespace.

---

# Gateway Validation

```bash
kubectl get gateway -n booking-dev
```

Observed result:

```text
NAME              CLASS                   ADDRESS         PROGRAMMED
booking-gateway   booking-gateway-class   192.168.59.11   True
```

The important condition is:

```text
PROGRAMMED = True
```

This confirms that the Gateway controller successfully provisioned the required data plane configuration.

---

# Initial Gateway Issue

The Gateway was initially configured with:

```yaml
gatewayClassName: booking-gateway-class
```

but no corresponding `GatewayClass` existed.

The Gateway remained in:

```text
Accepted:
  Status: Unknown
  Reason: Pending

Programmed:
  Status: Unknown
  Reason: Pending

Message:
  Waiting for controller
```

The resource existed in Kubernetes but no controller claimed responsibility for it.

---

# Resolution

A `GatewayClass` associated with Envoy Gateway was created:

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: GatewayClass

metadata:
  name: booking-gateway-class

spec:
  controllerName: gateway.envoyproxy.io/gatewayclass-controller
```

After applying it:

```text
GatewayClass
ACCEPTED=True
      |
      v
Gateway
PROGRAMMED=True
```

This demonstrates an important Kubernetes controller principle:

```text
Resource definition
        +
Controller
        =
Actual infrastructure behaviour
```

Creating a resource alone does not guarantee that anything will implement it.

---

# HTTPRoute

Two different paths must target two different Services.

The route is configured as:

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute

metadata:
  name: booking-route
  namespace: booking-dev

spec:
  parentRefs:
    - name: booking-gateway
      sectionName: http

  rules:
    - matches:
        - path:
            type: PathPrefix
            value: /api

      backendRefs:
        - name: booking-api
          port: 8080

    - matches:
        - path:
            type: PathPrefix
            value: /

      backendRefs:
        - name: booking-frontend
          port: 8080
```

The resulting routing model is:

```text
HTTPRoute
   |
   ├── PathPrefix /api
   |        |
   |        v
   |   booking-api:8080
   |
   └── PathPrefix /
            |
            v
       booking-frontend:8080
```

---

# HTTPRoute Issue Encountered

The first version incorrectly defined both path matches inside a single rule:

```yaml
rules:
  - matches:
      - path:
          value: /
      - path:
          value: /api

    backendRefs:
      - name: booking-frontend
      - name: booking-api
```

This does not mean:

```text
/     -> frontend
/api  -> backend
```

The two matches belong to the same rule and the two backends are associated with that same rule.

This can be interpreted as multiple matching conditions sharing multiple possible backends instead of explicit path-to-service mappings.

---

# Resolution

Each routing decision was moved into its own rule:

```text
Rule #1
/api
  |
  v
booking-api

Rule #2
/
  |
  v
booking-frontend
```

This explicitly models the desired platform routing.

---

# HTTPRoute Validation

```bash
kubectl describe httproute booking-route -n booking-dev
```

Observed conditions:

```text
Type:    Accepted
Status:  True

Type:    ResolvedRefs
Status:  True
```

These conditions confirm that:

```text
HTTPRoute accepted by controller
              +
Backend references successfully resolved
```

The referenced Services exist and can be used by Envoy Gateway.

---

# Control Plane vs Data Plane

Installing Envoy Gateway resulted in two distinct components.

```bash
kubectl get pods -n envoy-gateway-system
```

Observed resources:

```text
envoy-gateway-...
envoy-booking-dev-booking-gateway-...
```

Conceptually:

```text
CONTROL PLANE

Envoy Gateway Controller
        |
        | watches
        v
Gateway / HTTPRoute resources
        |
        | generates configuration
        v


DATA PLANE

Envoy Proxy
        |
        | handles actual HTTP traffic
        v
Kubernetes Services
```

The controller does not directly process application HTTP requests.

The Envoy proxy data plane does.

---

# Envoy Data Plane Service

Envoy Gateway automatically provisioned a Service for the Gateway.

```bash
kubectl get svc -n envoy-gateway-system
```

Observed result:

```text
NAME                                         TYPE
envoy-booking-dev-booking-gateway-...        LoadBalancer
```

with:

```text
EXTERNAL-IP
192.168.59.11
```

The application Services remain:

```text
booking-api        ClusterIP
booking-frontend   ClusterIP
```

Only the platform edge component is exposed.

This gives the desired architecture:

```text
External traffic
       |
       v
Envoy LoadBalancer
       |
       v
HTTPRoute
       |
   +---+---+
   |       |
Frontend   API
ClusterIP ClusterIP
```

---

# Docker Desktop Networking Issue

The Gateway was correctly provisioned:

```text
PROGRAMMED=True
```

and Envoy was running successfully.

However:

```bash
curl http://192.168.59.11:8080
```

returned:

```text
curl: (7) Failed to connect to 192.168.59.11 port 8080
```

The Gateway itself was not broken.

The address belonged to the internal Docker Desktop Kubernetes networking environment and was not directly reachable from the macOS host in this setup.

---

# Local Development Resolution

A local port-forward was created toward the Envoy Service.

```bash
kubectl -n envoy-gateway-system port-forward \
  service/envoy-booking-dev-booking-gateway-317b7f22 \
  8888:8080
```

This creates the following temporary path:

```text
macOS
 |
localhost:8888
 |
kubectl port-forward
 |
Envoy Service :8080
 |
HTTPRoute
 |
+-------------+
|             |
Frontend      API
```

The platform then became reachable through:

```text
http://localhost:8888
```

---

# Browser Validation

Opening:

```text
http://localhost:8888
```

successfully loaded the Angular frontend.

Angular API requests such as:

```text
/api/hotels
```

were routed through Envoy toward the backend.

The complete request path became:

```text
Browser
   |
   | GET /api/hotels
   v
localhost:8888
   |
kubectl port-forward
   |
Envoy Gateway
   |
HTTPRoute
   |
PathPrefix /api
   |
booking-api Service
   |
API Pods
```

---

# Before vs After

## Before Gateway API

```text
GET /
 |
 v
booking-frontend
 |
 Nginx
 |
 Angular
 |
 ✅ HTML
```

But:

```text
GET /api/hotels
 |
 v
booking-frontend
 |
 Nginx
 |
 SPA fallback
 |
 index.html
 |
 ❌ wrong response
```

The request returned:

```text
HTTP 200
Content-Type: text/html
```

instead of JSON.

---

## After Gateway API

```text
GET /
 |
 v
Envoy
 |
 HTTPRoute /
 |
 booking-frontend
 |
 Angular
 |
 ✅
```

and:

```text
GET /api/hotels
 |
 v
Envoy
 |
 HTTPRoute /api
 |
 booking-api
 |
 Spring Boot
 |
 ✅ JSON
```

The frontend and backend now share a single HTTP entry point.

---

# Troubleshooting Commands

Gateway API resources:

```bash
kubectl get gatewayclass

kubectl get gateway -n booking-dev

kubectl get httproute -n booking-dev
```

Detailed inspection:

```bash
kubectl describe gateway booking-gateway -n booking-dev

kubectl describe httproute booking-route -n booking-dev
```

Envoy Gateway control plane:

```bash
kubectl get pods -n envoy-gateway-system
```

Envoy Gateway Services:

```bash
kubectl get svc -n envoy-gateway-system
```

Application Services:

```bash
kubectl get svc -n booking-dev
```

Local access:

```bash
kubectl -n envoy-gateway-system port-forward \
  service/envoy-booking-dev-booking-gateway-317b7f22 \
  8888:8080
```

---

# Useful Gateway API Status Conditions

A useful troubleshooting sequence is:

```text
GatewayClass
   |
   └── Accepted=True
           |
           v
Gateway
   |
   ├── Accepted=True
   └── Programmed=True
           |
           v
HTTPRoute
   |
   ├── Accepted=True
   └── ResolvedRefs=True
```

If one of these conditions is false or unknown, investigation should start at that layer.

---

# Key Learnings

## Kubernetes resources require controllers

A Kubernetes API resource represents desired state.

A controller is responsible for turning that desired state into actual runtime behaviour.

```text
Gateway YAML
     +
Envoy Gateway Controller
     =
Configured Envoy data plane
```

---

## GatewayClass selects the implementation

```text
GatewayClass
     |
controllerName
     |
     v
Envoy Gateway
```

A Gateway referencing a nonexistent or unmanaged GatewayClass remains pending.

---

## Gateway represents infrastructure

The Gateway defines how traffic enters the platform.

```text
Gateway
 |
HTTP listener
 |
:8080
```

---

## HTTPRoute represents application routing

The HTTPRoute determines where traffic goes after entering the platform.

```text
/api -> backend
/    -> frontend
```

---

## Application Services remain internal

The application tier does not need individual external exposure.

```text
booking-frontend = ClusterIP
booking-api      = ClusterIP
```

Only the shared edge infrastructure is exposed.

---

## A successful HTTP status is not enough

The initial `/api/hotels` test returned:

```text
HTTP 200
```

but with:

```text
Content-Type: text/html
```

instead of:

```text
application/json
```

Correct troubleshooting must therefore validate both transport-level status and application-level behaviour.

---

## Local networking can differ from cluster networking

A Gateway can be:

```text
Accepted=True
Programmed=True
```

while its LoadBalancer IP remains unreachable from the developer workstation.

This does not automatically indicate a Gateway or routing failure.

The path between the workstation and the cluster must also be investigated.

---

# Result

PLAT-004 completed successfully.

```text
✅ Gateway API installed
✅ Envoy Gateway controller operational
✅ GatewayClass accepted
✅ Gateway programmed
✅ HTTPRoute accepted
✅ Backend references resolved
✅ / routed to Angular frontend
✅ /api routed to Spring Boot backend
✅ Application Services remain ClusterIP
✅ Envoy provides the shared platform edge
✅ Platform accessible locally through port-forward
✅ Full frontend-to-backend flow validated in browser
```

The Booking Platform now has a functional Kubernetes-native HTTP entry point based on Gateway API.

---

# Next Step

The next networking exercise will reproduce the same routing model using the traditional Kubernetes `Ingress` API.

This will allow a direct comparison between:

```text
Ingress
```

and:

```text
Gateway API
```

before continuing with externalized application configuration, secrets, health probes and production-readiness controls.
