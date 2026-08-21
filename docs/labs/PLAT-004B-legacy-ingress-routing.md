# PLAT-004B — Legacy Ingress Routing with Traefik

## Objective

Reproduce the same application routing already implemented with Gateway API, but using the traditional Kubernetes `Ingress` model.

Routing requirements:

```text
/       -> booking-frontend:8080
/api/*  -> booking-api:8080
```

The frontend and backend Services must remain internal:

```text
booking-frontend   ClusterIP
booking-api        ClusterIP
```

No direct `NodePort` or `LoadBalancer` should be added to the application Services.

---

## Architecture

```text
                      Client
                         |
                         v
                    Traefik
              Ingress Controller
                         |
                         v
                 booking-ingress
                    /       \
                   /         \
                  v           v
              /api/*          /
                |             |
                v             v
          booking-api   booking-frontend
            Service         Service
           ClusterIP       ClusterIP
              |               |
          +---+---+       +---+---+
          |       |       |       |
        API #1  API #2  Front #1 Front #2
```

---

## Why Ingress Is Included

The Kubernetes `Ingress` API is still widely encountered in existing enterprise environments.

Although Gateway API is the modern direction for new platform designs, Ingress remains relevant for:

- Existing clusters
- Legacy platforms
- Migration projects
- Older Helm charts
- Established internal platform standards

The objective of this lab is therefore to understand both models.

---

## Ingress Controller

An Ingress resource requires an Ingress Controller.

Traefik was installed through Helm.

```bash
helm repo add traefik https://traefik.github.io/charts
helm repo update
```

Installation:

```bash
helm upgrade --install traefik traefik/traefik \
  --namespace traefik-system \
  --create-namespace \
  --set ingressClass.name=traefik \
  --set ingressClass.isDefaultClass=false \
  --set providers.kubernetesIngress.enabled=true \
  --set providers.kubernetesIngress.ingressClass=traefik \
  --wait
```

---

## Controller Validation

```bash
kubectl get pods -n traefik-system
```

Observed result:

```text
NAME                       READY   STATUS    RESTARTS
traefik-754659b4f6-hlzcr   1/1     Running   0
```

Traefik is operational.

---

## IngressClass Validation

```bash
kubectl get ingressclass
```

Observed result:

```text
NAME      CONTROLLER                    PARAMETERS
traefik   traefik.io/ingress-controller   <none>
```

This confirms that the Kubernetes cluster has an IngressClass managed by Traefik.

---

## Ingress Resource

The application Ingress was created in the `booking-dev` namespace.

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress

metadata:
  name: booking-ingress
  namespace: booking-dev

spec:
  ingressClassName: traefik

  rules:
    - host: booking.local

      http:
        paths:
          - path: /api
            pathType: Prefix

            backend:
              service:
                name: booking-api

                port:
                  number: 8080

          - path: /
            pathType: Prefix

            backend:
              service:
                name: booking-frontend

                port:
                  number: 8080
```

---

## Routing Model

The Ingress defines two path-based rules.

```text
booking.local
      |
      +---- /api
      |       |
      |       v
      |   booking-api:8080
      |
      +---- /
              |
              v
       booking-frontend:8080
```

`pathType: Prefix` ensures that requests such as:

```text
/api
/api/hotels
/api/info
```

are matched by the `/api` rule.

---

## Ingress Validation

```bash
kubectl get ingress -n booking-dev
```

Observed result:

```text
NAME              CLASS     HOSTS           ADDRESS         PORTS
booking-ingress   traefik   booking.local   192.168.59.12   80
```

---

## Detailed Validation

```bash
kubectl describe ingress booking-ingress -n booking-dev
```

Observed routing:

```text
Host: booking.local

/api
  -> booking-api:8080
  -> 10.1.1.25:8080
  -> 10.1.1.16:8080

/
  -> booking-frontend:8080
  -> 10.1.1.35:8080
  -> 10.1.1.34:8080
```

This confirms that Traefik correctly resolves both Services and their Pod endpoints.

---

## Traefik Service

```bash
kubectl get svc -n traefik-system
```

Observed result:

```text
NAME      TYPE           CLUSTER-IP      EXTERNAL-IP      PORT(S)
traefik   LoadBalancer   10.98.160.246   192.168.59.12    80:32396/TCP,443:31998/TCP
```

The application Services remain internal.

```text
Traefik
LoadBalancer
     |
     v
Ingress
     |
 +---+---+
 |       |
Frontend API
ClusterIP ClusterIP
```

---

## Local Access

Because the LoadBalancer IP is part of Docker Desktop networking, local access was performed through port-forwarding.

```bash
kubectl port-forward \
  -n traefik-system \
  service/traefik \
  8889:80
```

A local hostname mapping was also added:

```text
127.0.0.1 booking.local
```

The application then became available through:

```text
http://booking.local:8889
```

---

## Frontend Validation

Request:

```bash
curl -i \
  -H "Host: booking.local" \
  http://localhost:8889/
```

Expected result:

```text
HTTP/1.1 200 OK
Content-Type: text/html
```

The response is served by the Angular frontend.

---

## Backend Validation

Request:

```bash
curl -i \
  -H "Host: booking.local" \
  http://localhost:8889/api/hotels
```

Expected result:

```text
HTTP/1.1 200 OK
Content-Type: application/json
```

The request is correctly routed to Spring Boot.

---

## Browser Validation

Opening:

```text
http://booking.local:8889
```

successfully loads the Angular application.

Angular requests to:

```text
/api/hotels
/api/info
```

are routed by Traefik toward `booking-api`.

Final request path:

```text
Browser
   |
booking.local:8889
   |
kubectl port-forward
   |
Traefik
   |
Ingress
   |
   +------------------+
   |                  |
 /api/*                /
   |                  |
booking-api      booking-frontend
   |                  |
API Pods          Frontend Pods
```

---

## Ingress vs Gateway API

The same application routing is now implemented using both models.

### Gateway API

```text
GatewayClass
     |
Gateway
     |
HTTPRoute
     |
Services
```

### Ingress

```text
IngressClass
     |
Ingress Controller
     |
Ingress
     |
Services
```

Comparison:

| Gateway API              | Ingress                              |
| ------------------------ | ------------------------------------ |
| GatewayClass             | IngressClass                         |
| Gateway                  | Controller infrastructure            |
| HTTPRoute                | Ingress                              |
| backendRefs              | backend.service                      |
| Route-specific resources | Routing concentrated in one resource |
| Modern platform model    | Legacy / widely deployed model       |

---

## Key Learnings

- An Ingress resource requires an Ingress Controller.
- `IngressClass` identifies which controller handles the resource.
- `pathType: Prefix` is appropriate for path groups such as `/api/*`.
- Application Services can remain `ClusterIP`.
- The edge proxy is the only externally exposed component.
- Host-based routing requires the HTTP `Host` header to match the configured host.
- Ingress and Gateway API can expose the same application architecture using different abstractions.
- Legacy knowledge remains useful for existing enterprise clusters.

---

## Result

PLAT-004B completed successfully.

```text
✅ Traefik installed
✅ IngressClass created
✅ booking-ingress created
✅ / routed to Angular
✅ /api routed to Spring Boot
✅ Application Services remain ClusterIP
✅ Ingress routing validated
✅ Browser flow validated
✅ Legacy Ingress model compared with Gateway API
```

The platform now supports both the traditional Kubernetes Ingress model and the newer Gateway API model.
