# Kubernetes deployment

For a concept-by-concept explanation, architecture overview, troubleshooting
guide, and interview questions, read
[KUBERNETES-INTERVIEW-GUIDE.md](KUBERNETES-INTERVIEW-GUIDE.md).

Create the shared database Secret before applying the manifests. Do not put the
password in these YAML files or commit a Secret manifest to source control:

```sh
read -rsp "Postgres password: " POSTGRES_PASSWORD
printf '\n'
if [ -n "$POSTGRES_PASSWORD" ]; then
  printf 'username=store_user\npassword=%s\n' "$POSTGRES_PASSWORD" |
    kubectl create secret generic postgres-secret --from-env-file=/dev/stdin &&
    kubectl apply -f k8s/
else
  printf 'A non-empty password is required.\n' >&2
fi
unset POSTGRES_PASSWORD
```

The Secret must contain `username` and `password` keys. Configure Kubernetes
Secret encryption at rest and restrict Secret access with RBAC in production.
Make sure the `store:0.0.1` image is available to the cluster before applying
the app Deployment (for example, push it to a registry or load it into a local
cluster). The app Service is a NodePort on `30080`; restrict node access or use
an ingress/ClusterIP setup if it should not be directly reachable from the
network.
