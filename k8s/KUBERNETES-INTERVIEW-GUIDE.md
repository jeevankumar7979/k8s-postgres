# Kubernetes: project and interview guide

This guide introduces Kubernetes concepts, explains how they fit together, and
maps them to the Store project's manifests in this directory. The examples use
Docker Desktop and the resources in `app.yml` and `postgres.yml`.

For the database password setup, see [README.md](README.md). Never commit a
real password or a populated Secret manifest.

## 1. What Kubernetes does

Kubernetes (K8s) is a container orchestration platform. You declare the
desired state of an application, and Kubernetes continually works to make the
actual cluster state match it. Kubernetes can schedule containers, restart
failed containers, replace failed Pods, provide service discovery, attach
storage, and coordinate application updates.

Kubernetes is not a container runtime or a build system. Build and publish the
container image separately; Kubernetes runs that image on cluster nodes.

### Desired state and reconciliation

Most Kubernetes resources are declarative API objects. For example, a
Deployment with `replicas: 3` declares that three matching application Pods
should be available. Controllers watch the API server, compare desired state
with observed state, and take corrective action. If one Pod disappears, the
Deployment controller creates a replacement through a ReplicaSet.

This control loop is called reconciliation. A successful `kubectl apply` means
the API server accepted a configuration; it does not necessarily mean the
application is already healthy. Use rollout status and inspect Pods to confirm
the result.

## 2. Cluster architecture

### Control plane

- **kube-apiserver** is the Kubernetes API front door. `kubectl` sends requests
  to it, and cluster components watch and update objects through it.
- **etcd** stores Kubernetes cluster state. It is a critical control-plane
  datastore and must be backed up in clusters that you operate.
- **kube-scheduler** assigns newly created, unscheduled Pods to nodes based on
  available resources, constraints, affinities, and policies.
- **controller manager** runs controllers that reconcile resources, such as
  Deployments, ReplicaSets, Nodes, and endpoints.
- **cloud controller manager** integrates with a cloud provider for resources
  such as load balancers, routes, and volumes. It may not be present in a local
  Docker Desktop cluster.

### Worker node

- **kubelet** runs on each node. It observes Pods assigned to that node and
  asks the container runtime to create and monitor their containers.
- **Container runtime** (for example, containerd) pulls images and runs
  containers. Kubernetes uses the Container Runtime Interface (CRI) to
  communicate with it.
- **kube-proxy** or an equivalent dataplane implements Service forwarding.
- **CNI plugin** provides Pod networking. Each Pod gets a cluster IP and can
  communicate with other Pods according to the network plugin and policies.
- **CSI driver** provides persistent storage through Kubernetes' storage
  interfaces.

### What happens after `kubectl apply`

1. `kubectl` sends the manifest to the API server, which authenticates and
   authorizes the request, validates it, and persists the object.
2. Controllers notice the new object. A Deployment controller creates a
   ReplicaSet, which creates Pods until the requested replica count is met.
3. The scheduler chooses nodes for unscheduled Pods.
4. The kubelet on each selected node retrieves the Pod specification. The
   runtime pulls any missing image and creates the container.
5. The CNI configures networking. Volumes are attached and mounted when
   requested.
6. The kubelet runs startup, readiness, and liveness probes. Ready Pod IPs are
   made available through the Service's endpoints.
7. The Service dataplane routes connections to eligible endpoints.

If a step fails, inspect Events (`kubectl describe`) and container logs rather
than assuming the API accepted means the app started.

## 3. Core Kubernetes objects

### Pod

A Pod is Kubernetes' smallest schedulable unit. It contains one or more
co-located containers that share a network identity and can share volumes.
Containers in a Pod are not independently scheduled. Pods are replaceable;
their IPs and local files are not durable identities.

The Store application container listens on port 8080. PostgreSQL listens on
5432. The Pod's container port declaration documents the port but does not by
itself publish it outside the Pod.

### Deployment and ReplicaSet

A Deployment manages stateless, interchangeable Pods. Its template describes
the Pod and its replica count. A Deployment controller creates and updates a
ReplicaSet, which ensures the requested count of Pods exists.

The Store Deployment requests three replicas. They all use image
`store:0.0.1`; Service traffic can be routed to any ready replica. During a
rolling update, Kubernetes can gradually replace old Pods with new ones.

Deployment is a good fit for web/API processes that can be restarted or moved
without preserving a particular Pod identity. It does not make an application
highly available by itself: use multiple replicas, appropriate resource
capacity, health checks, and resilient dependencies.

### StatefulSet

A StatefulSet is intended for applications that need stable Pod identities or
per-replica storage. Pods receive predictable ordinal names such as
`postgres-0` and `postgres-1`; volume claim templates can create a persistent
volume claim per ordinal.

A StatefulSet does **not** automatically replicate application data or provide
database failover. Each PostgreSQL Pod created from the current manifest has
its own data volume. Two independent PostgreSQL containers are two independent
databases unless PostgreSQL replication and a failover design are configured.

**Important project caveat:** `postgres.yml` currently sets `replicas: 2`,
while both Pods use the standard single-instance PostgreSQL image. The
headless Service named `postgres` can resolve to either Pod, and the JDBC URL
uses that Service name. Consequently, application replicas may connect to
different, unreplicated databases. For a simple development setup, run one
PostgreSQL replica. For production high availability, use a PostgreSQL
operator or a carefully designed replication/failover solution with a
primary-aware endpoint, backups, monitoring, and tested recovery procedures.

### Service

A Service provides a stable virtual address and DNS name for a changing set of
Pods. It selects Pods using labels and sends traffic to ready endpoints that
match its selector.

The Store Service selects Pods labeled `app: store` and routes port 8080 to
their container port 8080. The application uses the `postgres` DNS name to
connect to the PostgreSQL Service on port 5432.

Common Service types:

- **ClusterIP** (default): reachable from inside the cluster.
- **NodePort**: exposes the Service on a port on each node, often in the
  30000-32767 range.
- **LoadBalancer**: asks a supported cloud or local integration to provision
  an external load balancer.
- **ExternalName**: maps a Service name to an external DNS name.

The Store Service is a NodePort on 30080. Docker Desktop may not forward that
port to Mac `localhost`. For local access, run `kubectl port-forward
service/store 30080:8080` and connect to `http://127.0.0.1:30080`. Port
forwarding is temporary and must remain running.

### Headless Service and DNS

The PostgreSQL Service sets `clusterIP: None`, making it headless. A headless
Service does not provide the usual single virtual ClusterIP; DNS can return
the IPs of matching Pods. StatefulSets commonly use headless Services to give
Pods stable DNS names such as `postgres-0.postgres`.

In this project the client URL is `jdbc:postgresql://postgres:5432/storedb`.
With multiple matching PostgreSQL Pods, the generic service DNS name is not a
primary-aware database endpoint.

### ConfigMap and Secret

A ConfigMap stores non-confidential configuration. `store-config` supplies
the JDBC URL to the Store container.

A Secret stores sensitive values such as credentials. `postgres-secret`
provides `username` and `password` keys to both PostgreSQL and the Store
application. Environment variable references do not place literal values in
the Pod manifest, but a Kubernetes Secret is not automatically encrypted in
etcd in every cluster. Production clusters should configure encryption at
rest, restrict Secret reads with RBAC, and use an external secret manager or
rotation process where appropriate.

ConfigMap or Secret values consumed as environment variables are read when a
container starts. Restart/roll out Pods after changing such values. Do not
print secrets in logs or pass them in shell commands that expose them in
process listings or shell history.

### PersistentVolume and PersistentVolumeClaim

A PersistentVolume (PV) represents storage available to a cluster. A
PersistentVolumeClaim (PVC) is a request for storage, including size and
access mode. A StorageClass can dynamically provision a PV when a matching
PVC is created.

The PostgreSQL StatefulSet's `volumeClaimTemplates` request a 5Gi
ReadWriteOnce claim for each ordinal. This gives each Pod a separate volume.
Deleting a Pod normally does not delete its PVC, but StatefulSet retention
policies and cluster/storage-provider behavior should be checked before
deleting resources. Persistent storage is not a backup: take and test database
backups separately.

### Namespace and labels

A Namespace scopes names and many policy controls. Resources such as Services
and Secrets are namespaced; the `postgres-secret` must exist in the same
namespace as the Pods that reference it. `kubectl` uses the current namespace
unless `-n` is supplied.

Labels are key/value metadata used by selectors. For example, the Store
Service selector `app: store` must match the Deployment Pod template label
`app: store`. If they differ, the Service has no endpoints.

## 4. Health checks and traffic

Kubernetes has distinct probes with different outcomes:

- **Startup probe** gives a slow-starting app time to initialize before
  liveness checks begin.
- **Liveness probe** detects a process that is alive but stuck. On repeated
  failure, kubelet restarts the container.
- **Readiness probe** indicates whether a Pod should receive Service traffic.
  A failed readiness probe removes it from eligible endpoints without
  necessarily restarting it.

The Store app uses Spring Boot Actuator's `/livez` and `/readyz` endpoints.
Readiness matters during startup and rollouts: a process can be running but
not ready to serve requests.

An HTTP 404 from `/` may be expected because this project exposes its API
under `/api/products`; check `/actuator/health` for Actuator health. Do not
interpret an unknown root path as a connectivity failure if HTTP is returned.

## 5. Networking overview

- Each Pod receives a cluster IP from the CNI network.
- Pods generally communicate directly with one another across nodes.
- Services provide stable virtual addresses and load-balance traffic to
  eligible endpoints.
- Cluster DNS resolves Service names, commonly
  `<service>.<namespace>.svc.cluster.local`.
- Ingress is an HTTP/HTTPS routing API; an Ingress controller must be
  installed to implement it.
- NetworkPolicy can restrict Pod ingress/egress when supported by the CNI.

`containerPort` is not the same as a Service or external port. A typical
request path is client -> Ingress/LoadBalancer/NodePort -> Service -> ready
Pod IP -> container port.

## 6. Resources, scaling, and updates

### Requests and limits

Container resource **requests** influence scheduling and reserve expected
capacity. **Limits** cap resource use; exceeding a CPU limit causes throttling,
while exceeding a memory limit can cause an OOM kill. The current manifests do
not set CPU or memory requests/limits, so production deployments should size
and test them.

### Scaling

Manually scale the app with `kubectl scale deployment/store --replicas=4`.
Horizontal Pod Autoscaler (HPA) can adjust replica count based on metrics such
as CPU utilization, but requires metrics and suitable resource requests.
Scaling the Store app does not scale PostgreSQL safely; databases need their
own capacity and replication plan.

Each Store replica configures a Hikari pool with maximum size 10. At three
replicas, the application can open up to roughly 30 database connections, in
addition to other clients. When scaling, consider the database's connection
limit and pool size.

### Rolling updates and rollback

Updating a Deployment's Pod template (for example, changing an image tag)
creates a new ReplicaSet. Kubernetes gradually replaces Pods, using readiness
to avoid sending traffic to unready new Pods. Useful commands:

```sh
kubectl rollout status deployment/store
kubectl rollout history deployment/store
kubectl rollout undo deployment/store
```

Prefer immutable versioned image tags or digests over reusing `latest`. Ensure
the image is available to every cluster node or its registry.

## 7. Security essentials

- Use non-root containers where possible. The Store image runs as UID 10001.
- Use least-privilege RBAC for humans and ServiceAccounts.
- Keep credentials out of source control; restrict Secret access and enable
  Secret encryption at rest.
- Use trusted, patched images and scan them; pin versions or image digests.
- Set resource requests and limits to reduce noisy-neighbor risk.
- Apply NetworkPolicies where supported and expose only necessary ports.
- Avoid privileged containers and unnecessary Linux capabilities.
- Store backups securely and periodically test restoration.

## 8. Project deployment map

| Project resource | Purpose |
| --- | --- |
| `store-config` ConfigMap | Supplies the JDBC URL `jdbc:postgresql://postgres:5432/storedb`. |
| `postgres-secret` Secret | Supplies DB username/password; create it before applying manifests. |
| `store` Deployment | Runs three stateless Spring Boot replicas on port 8080. |
| `store` NodePort Service | Routes port 30080 on nodes to ready Store Pods on port 8080. |
| `postgres` headless Service | Provides DNS for PostgreSQL StatefulSet Pods on port 5432. |
| `postgres` StatefulSet | Creates PostgreSQL Pods and per-Pod persistent claims. Currently configured for two independent PostgreSQL instances; this is not replication. |

The Dockerfile builds a Java 21 application jar using `./mvnw`, then copies
only the jar into a Java 21 runtime image. The Kubernetes manifests refer to
`store:0.0.1`; the cluster must be able to obtain that image.

## 9. Deploy and inspect this project

Run commands from the project root unless noted. Create the database Secret
using the safe prompt in [README.md](README.md), then apply resources:

```sh
kubectl config current-context
kubectl apply -f k8s/
kubectl get deployments,statefulsets,services,pvc
kubectl get pods -o wide
kubectl rollout status deployment/store
```

Useful diagnosis commands:

```sh
kubectl describe pod <pod-name>
kubectl get events --sort-by=.metadata.creationTimestamp
kubectl logs deployment/store
kubectl logs statefulset/postgres --all-pods=true
kubectl get endpoints store
kubectl get secret postgres-secret
kubectl port-forward service/store 30080:8080
```

Use `kubectl logs -p <pod-name>` for the previous container instance after a
restart. Avoid commands that print Secret data in shared terminals or logs.
`kubectl get secret postgres-secret` shows metadata, not the credential values.

Common symptoms:

| Symptom | Likely causes and checks |
| --- | --- |
| `CreateContainerConfigError` | Missing Secret/key or ConfigMap/key; inspect Pod Events with `kubectl describe pod`. |
| `ImagePullBackOff` | Image/tag unavailable or registry authentication failure. |
| `Pending` | Insufficient node resources, PVC not bound, or scheduling constraints. |
| Running but not Ready | Readiness probe failure, app initialization issue, or unavailable dependency; inspect Events and logs. |
| Service has no endpoints | Selector/label mismatch or all selected Pods are unready. |
| Connection refused at `localhost:30080` | NodePort may not be exposed on the local host; use `kubectl port-forward` for Docker Desktop. |
| App connects to inconsistent database state | Multiple independent PostgreSQL Pods behind one name; use one DB instance for simple development or configure real replication/failover. |

## 10. Interview questions and concise answers

**What is the difference between a Pod and a container?**  
A container is a runtime process with an isolated filesystem and namespaces.
A Pod is the Kubernetes scheduling unit that wraps one or more related
containers sharing network identity and optionally volumes.

**What is the difference between a Deployment and a StatefulSet?**  
A Deployment manages interchangeable replicas suited to stateless apps. A
StatefulSet provides stable ordinal identity and per-replica storage patterns
for stateful apps; it does not implement data replication by itself.

**What does a Service solve?**  
Pod IPs change as Pods are replaced. A Service provides a stable virtual
address and DNS name, selecting matching Pods and routing traffic to ready
endpoints.

**What happens when a container exits?**  
The kubelet observes the container state and applies the Pod's restart policy.
A controller such as a Deployment's ReplicaSet also maintains the desired Pod
count, creating a replacement Pod if a Pod is lost.

**What is the difference between readiness and liveness?**  
Readiness controls whether a Pod receives Service traffic. Liveness detects
stuck containers and can trigger a restart. A failed readiness check should
not automatically be treated as proof the process must be restarted.

**What happens during `kubectl apply`?**  
The API server validates and stores the desired object. Controllers reconcile
it; a scheduler assigns Pods to nodes; kubelets and runtimes create containers;
network and storage plugins configure connectivity and volumes; readiness
determines when traffic can be sent.

**How does a Pod find a database Service?**  
Cluster DNS resolves the Service name to a cluster address or, for a
headless Service, to endpoint Pod addresses. In this project the client uses
`postgres:5432` from the same namespace.

**Does a Secret automatically encrypt a password?**  
No. Base64 in serialized Secret data is encoding, not encryption. Protect
access with RBAC, configure encryption at rest, and consider external secret
management and rotation.

**How would you troubleshoot a Pod stuck in Pending?**  
Start with `kubectl describe pod` and Events. Check scheduling/resource
constraints, node capacity, PVC binding, storage class, affinity, and taints.

**How would you troubleshoot a Service that cannot reach an app?**  
Check the Service selector against Pod labels, inspect ready endpoints, test
Pod readiness and container port, then check DNS/network policy and probe
status. From outside the cluster, verify that an Ingress, LoadBalancer, or
NodePort is actually reachable.

**Does two replicas of PostgreSQL in a StatefulSet mean high availability?**  
No. The StatefulSet supplies identity and storage, not database replication,
primary election, or failover. A database-specific replication and recovery
design is required.

**How do you scale an application safely?**  
Scale stateless app replicas based on demand and capacity, use readiness
checks, set resource requests/limits, and check downstream limits. Here,
three app replicas can use up to 30 pooled DB connections with the configured
maximum of 10 per replica, so database connection capacity must be considered.

## 11. A useful mental model

Think of Kubernetes as a collection of APIs plus controllers that continually
reconcile declared state. A Deployment asks for Pods; the scheduler places
them; kubelets run them; probes report health; a Service tracks ready Pod
endpoints and supplies stable discovery; ConfigMaps, Secrets, and volumes
provide configuration, credentials, and persistence. When behavior differs
from expectation, inspect each boundary in that request and reconciliation
path, especially Events and ready endpoints.
