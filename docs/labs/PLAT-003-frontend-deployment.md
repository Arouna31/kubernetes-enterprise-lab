# PLAT-003 — Deploy Frontend on Kubernetes

## Objective

Deploy the Angular frontend inside the existing `booking-dev` namespace.

The frontend must:

- Run with 2 replicas
- Be reachable from other workloads inside the cluster
- Be accessible through the DNS name `booking-frontend`
- Remain internal to the Kubernetes cluster
- Not expose the backend directly
- Not use Ingress yet

---

## Architecture

```text
                    Kubernetes Cluster
                           |
                    booking-dev
                           |
          +----------------+----------------+
          |                                 |
          |     booking-frontend Service    |
          |          ClusterIP              |
          |            :8080                |
          |                                 |
          +----------------+----------------+
                           |
                    app=booking-frontend
                           |
              +------------+------------+
              |                         |
              v                         v
      frontend Pod #1           frontend Pod #2
           :8080                     :8080


          +-----------------------------------+
          |        booking-api Service        |
          |            ClusterIP              |
          |              :8080                |
          +----------------+------------------+
                           |
                    app=booking-api
                           |
              +------------+------------+
              |                         |
              v                         v
          API Pod #1                 API Pod #2
```

At this stage, both the frontend and backend are independently available inside the cluster.

---

## Kubernetes Resources

The frontend deployment introduces two new resources:

```text
Namespace
└── booking-dev

Deployment
├── booking-api
└── booking-frontend

Service
├── booking-api
└── booking-frontend
```

The frontend uses the same Kubernetes pattern already implemented for the backend:

```text
Deployment
    +
Service
```

---

## Frontend Deployment

The Angular application is deployed using a Kubernetes `Deployment`.

Example structure:

```yaml
apiVersion: apps/v1
kind: Deployment

metadata:
  name: booking-frontend
  namespace: booking-dev

spec:
  replicas: 2

  selector:
    matchLabels:
      app: booking-frontend

  template:
    metadata:
      labels:
        app: booking-frontend

    spec:
      containers:
        - name: booking-frontend
          image: kube-lab/frontend:0.1.0

          ports:
            - containerPort: 8080
```

The Deployment keeps two frontend instances running.

---

## Frontend Service

The frontend must only be reachable inside the Kubernetes cluster at this stage.

A `ClusterIP` Service is therefore used.

```yaml
apiVersion: v1
kind: Service

metadata:
  name: booking-frontend
  namespace: booking-dev

spec:
  type: ClusterIP

  selector:
    app: booking-frontend

  ports:
    - protocol: TCP
      port: 8080
      targetPort: 8080
```

The Service selects Pods containing:

```yaml
app: booking-frontend
```

---

## Validation

### Check all resources

```bash
kubectl get all -n booking-dev
```

Observed state:

```text
NAME                                     READY   STATUS    RESTARTS
pod/booking-api-d9d8bc57d-7f27q          1/1     Running   0
pod/booking-api-d9d8bc57d-rmqqx          1/1     Running   0
pod/booking-frontend-cbfd5c4f8-khswb     1/1     Running   0
pod/booking-frontend-cbfd5c4f8-zzz55     1/1     Running   0
```

Both backend and frontend Deployments have two healthy replicas.

---

### Check Services

```bash
kubectl get svc -n booking-dev
```

Observed state:

```text
NAME               TYPE        CLUSTER-IP       EXTERNAL-IP   PORT(S)
booking-api        ClusterIP   10.100.106.143   <none>        8080/TCP
booking-frontend   ClusterIP   10.105.121.161   <none>        8080/TCP
```

Neither application is exposed through `NodePort` or `LoadBalancer`.

---

## Internal Connectivity Test

A temporary debug Pod was used to access the frontend through Kubernetes internal DNS.

```bash
kubectl run curl \
  --image=curlimages/curl \
  --rm -it \
  --restart=Never \
  -n booking-dev \
  -- curl http://booking-frontend:8080
```

The request successfully returned the Angular application HTML:

```html
<!doctype html>
<html lang="fr">
  <head>
    <meta charset="utf-8" />
    <title>Kubernetes Enterprise Lab</title>
    <base href="/" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
  </head>

  <body>
    <app-root></app-root>
  </body>
</html>
```

This confirms the following network path:

```text
Debug Pod
    |
    | Kubernetes DNS
    v
booking-frontend
    |
ClusterIP Service
    |
    +----------------+
    |                |
    v                v
Frontend Pod 1   Frontend Pod 2
    |
   Nginx
    |
 Angular
```

---

## What Was Validated

The frontend is now independently operational inside Kubernetes.

```text
✅ Deployment created
✅ 2 frontend replicas running
✅ ClusterIP Service created
✅ Kubernetes DNS working
✅ Frontend reachable from another Pod
✅ Nginx serves the Angular application
✅ No direct external exposure
```

---

## Current Limitation

Although the Angular frontend itself is reachable, communication with the backend is not yet correctly routed.

The Angular application sends API requests using:

```text
/api/hotels
/api/info
```

During local development, Angular CLI handled this using:

```text
proxy.conf.json
```

Conceptually:

```text
Angular Dev Server
       |
       | /api/*
       v
localhost:8080
       |
   Spring Boot
```

However, the production container does not run `ng serve`.

It runs Nginx instead.

The runtime architecture is therefore:

```text
Browser
   |
   | /
   v
Nginx
   |
 Angular
```

For an API request:

```text
Browser
   |
   | /api/hotels
   v
Frontend Nginx
   |
   X
No API routing configured
```

The Angular development proxy does not exist inside the production container.

---

## Architectural Decision

The frontend container will not be modified to proxy backend requests.

Instead, a shared HTTP entry point will be introduced in the next stage.

Target architecture:

```text
                    Browser
                       |
                       v
                HTTP Entry Point
                  /           \
                 /             \
                v               v
       booking-frontend      booking-api
          Service             Service
             |                   |
         Front Pods            API Pods
```

Routing rules will eventually look like:

```text
/       -> booking-frontend:8080
/api/*  -> booking-api:8080
```

This keeps routing responsibilities outside the application containers.

---

## Why ClusterIP Is Used

The frontend currently does not need direct exposure outside the cluster.

Therefore:

```text
ClusterIP
```

is sufficient.

This gives the frontend a stable internal address:

```text
booking-frontend
```

while allowing the underlying Pod IP addresses to remain ephemeral.

---

## Service Discovery

Kubernetes DNS provides a stable name for the frontend Service.

Inside the same namespace:

```text
booking-frontend
```

can be resolved directly.

Its fully qualified DNS form is conceptually:

```text
booking-frontend.booking-dev.svc.cluster.local
```

This removes any dependency on individual Pod IP addresses.

---

## Labels and Selectors

The frontend Service discovers its Pods using labels.

Pod labels:

```yaml
labels:
  app: booking-frontend
```

Service selector:

```yaml
selector:
  app: booking-frontend
```

This relationship is critical.

If the selector does not match the Pod labels:

```text
Service
   |
   X
No backend endpoints
```

---

## Troubleshooting Commands

Useful commands for validating the frontend deployment:

```bash
kubectl get all -n booking-dev

kubectl get pods -n booking-dev

kubectl get pods -n booking-dev -o wide

kubectl get svc -n booking-dev

kubectl describe deployment booking-frontend -n booking-dev

kubectl describe service booking-frontend -n booking-dev
```

Check the frontend EndpointSlice:

```bash
kubectl get endpointslices \
  -n booking-dev \
  -l kubernetes.io/service-name=booking-frontend
```

Internal connectivity test:

```bash
kubectl run curl \
  --image=curlimages/curl \
  --rm -it \
  --restart=Never \
  -n booking-dev \
  -- curl http://booking-frontend:8080
```

---

## Key Learnings

### Deployment + Service Pattern

A common stateless application pattern is:

```text
Deployment
    |
ReplicaSet
    |
  Pods
    |
Service
```

The Deployment handles application lifecycle and replicas.

The Service provides stable networking.

---

### Pods Are Ephemeral

Frontend Pods may be replaced at any moment.

Their IP addresses are therefore not reliable application endpoints.

The Service hides this volatility:

```text
booking-frontend
      |
   Service
      |
  +---+---+
  |       |
Pod A   Pod B
```

---

### Application Runtime Differs From Development Runtime

Local Angular development uses:

```text
ng serve
```

and may rely on:

```text
proxy.conf.json
```

The production image uses:

```text
Nginx
```

Therefore development proxy behavior must not be assumed to exist in Kubernetes.

---

### Internal Networking Is Working

The successful request:

```text
http://booking-frontend:8080
```

from another Pod validates:

```text
Kubernetes DNS
      +
ClusterIP Service
      +
Service selector
      +
Pod networking
      +
Nginx container
      +
Angular static build
```

---

## Result

PLAT-003 completed successfully.

```text
✅ booking-frontend Deployment
✅ 2 frontend replicas
✅ Internal ClusterIP Service
✅ Kubernetes service discovery
✅ Frontend accessible from another workload
✅ Angular served successfully through Nginx
✅ Backend remains internal
✅ No external routing introduced yet
```

The platform now contains two independently operating application tiers:

```text
booking-frontend
        +
booking-api
```

The next step is to introduce a common HTTP routing layer so that browser requests can reach both services correctly.
