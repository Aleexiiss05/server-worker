# Server Worker

The **Server Worker** is a standalone Java service used inside games infrastructure.  
It listens to RabbitMQ queues and performs long-running or “infrastructure” tasks such as:

- deploying new servers (hubs / games)
- deleting existing servers
- updating server state in the database

It’s basically the **background worker** behind an admin panel:  
the panel sends a message → the worker does the heavy lifting safely, outside of Velocity and outside of the web server.
Please note that this project currently supports only single Minecraft instances such as Velocity and Paper.  
Multi-proxy networks are not supported yet. In the future, the system will be extended to handle multi-proxy deployments.

This project is also provided mainly as a showcase.  
It will not work out-of-the-box for everyone due to missing panel integrations and private configuration files.

---

## ✨ What this project does

### 🟢 Deploying servers

The worker consumes deployment messages from RabbitMQ (for example from a `deploy-server` queue).  
When it receives a message, it:

- reads the server type (hub / game, etc.)
- generates a unique server name
- chooses a free port in the allowed range
- builds a Kubernetes manifest from a template (using the server type and options)
- applies the manifest with `kubectl` to start a new pod
- inserts or updates the corresponding row in MySQL with status `LOADING`
- after a short delay, updates status to `ONLINE` so that Velocity / the panel can route players there

This flow is designed for:
- hub servers
- mini-game servers (Skywars, etc.)
- and can be extended to any other server type as long as a template exists

---

### 🔴 Deleting servers

The worker also consumes deletion messages (for example from a `delete-server` queue).  
When it receives a delete request, it:

- finds the target server in the database
- deletes the Kubernetes resources (Deployment, Service, etc.) with `kubectl`
- removes or updates the row in the MySQL table
- leaves the global state clean so the panel and Velocity don’t see “ghost” servers

This avoids having to run shell scripts manually or from the web panel.

---

### 🔁 Keeping the infrastructure consistent

Because all deploy/delete operations pass through the worker, it becomes the **single source of truth** for:

- when a server should exist in Kubernetes
- when a server should be visible in the database
- what its status is (`LOADING` / `ONLINE` / etc.)

That means:
- no more half-deleted pods still listed in MySQL
- no more DB entries for pods that don’t exist anymore
- Velocity and the panel can trust the DB state

---

## 🧱 Tech stack

The project is written to be simple but production-friendly:

- **Java 17**
- **Maven** for build and dependencies
- **RabbitMQ (AMQP)** as the message broker for deploy/delete tasks
- **MySQL** for server registry and status
- **K3s / Kubernetes** for running the Minecraft servers
- **kubectl** for applying manifests from the worker
- **Docker** support via the included `Dockerfile`
- **SLF4J / logging** for worker logs

---

## 🛠 Requirements

To run this worker in your environment, you need:

- A Linux machine (Debian recommended)
- Java 17
- Access to a **MySQL** database (with the Hylaria schema)
- Access to a **RabbitMQ** instance (for the queues used by the worker)
- A **Kubernetes / K3s** cluster with `kubectl` configured
- Network access so the worker can:
  - reach RabbitMQ
  - reach MySQL
  - talk to the Kubernetes API (through `kubectl`)

Runtime configuration (DB URL, RabbitMQ host, etc.) is provided via standard Java config (e.g. `application.properties` / env vars depending on your setup).

---
