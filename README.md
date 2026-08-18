# Kubernetes Enterprise Lab

A small public demo application designed to practice Kubernetes/OpenShift without exposing any private business project.

## Stack

- Angular frontend
- Spring Boot REST API
- Docker / Nginx
- Kubernetes and OpenShift manifests will be added progressively during the lab

## Demo architecture

```text
Browser
  |
  v
Angular frontend
  |
  | /api/*
  v
Spring Boot API
```

The backend deliberately exposes the running instance hostname so that Kubernetes load balancing, replicas, rollouts and troubleshooting can be observed easily.

## API

- `GET /api/hotels`
- `GET /api/info`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`

## Run locally

### Backend

Requires Java 21+ and Maven.

```bash
cd backend
mvn spring-boot:run
```

Backend: http://localhost:8080

### Frontend

Requires Node.js and npm.

```bash
cd frontend
npm install
npm start
```

Frontend: http://localhost:4200

Angular's development proxy forwards `/api` to `http://localhost:8080`.

## Build container images

```bash
docker build -t kube-lab/frontend:dev ./frontend
docker build -t kube-lab/booking-api:dev ./backend
```

## Next lab stages

The repository intentionally starts without ready-made Kubernetes manifests.

We will progressively add:

1. Namespace
2. Deployments
3. Services
4. ConfigMap / Secret
5. Ingress
6. Requests / limits
7. Liveness / readiness / startup probes
8. Rolling update / rollback
9. HPA / PDB
10. RBAC / ServiceAccount
11. NetworkPolicy
12. Kustomize
13. Argo CD
14. OpenShift migration

## Why the application is simple

The application code is intentionally lightweight. The goal of this repository is to demonstrate platform engineering and Kubernetes operations, not application-domain complexity.
