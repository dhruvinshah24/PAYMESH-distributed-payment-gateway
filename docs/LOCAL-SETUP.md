# PAYMESH — No-Docker Team Setup

This version runs with ordinary local processes. Docker is not required.

## Recommended database

Use **MySQL Server 8.x** locally. PAYMESH already uses the MySQL JDBC driver and cross-schema SQL for the application-level primary/backup demonstration, so switching to MongoDB or SQLite would add unnecessary compatibility work.

Install:
- JDK 17+
- Maven 3.9+
- Node.js 20+
- MySQL Server 8.x
- Optional: MySQL Workbench

## 1. Configure MySQL

The default project credentials are:

- host: `localhost`
- port: `3306`
- user: `root`
- password: `root`

If your password is different, either:

```powershell
$env:DB_USER='root'
$env:DB_PASSWORD='YOUR_PASSWORD'
$env:DB_HOST='localhost'
$env:DB_PORT='3306'
```

or edit `backend/src/main/resources/application.properties`.

## 2. Create the databases and tables

PowerShell:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\setup-mysql.ps1
```

The script searches for `mysql.exe` on PATH and common MySQL/XAMPP locations, then imports `database/schema.sql`.

## 3. Start backend

```powershell
.\scripts\start-backend.ps1
```

Check:

```text
http://localhost:8080/api/health
```

Expected:

```json
{"service":"PAYMESH","status":"UP"}
```

## 4. Start frontend

Open another PowerShell window:

```powershell
.\scripts\start-frontend.ps1
```

Open:

```text
http://localhost:5173
```

## 5. One-command launcher

After MySQL is installed and configured:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\start-paymesh.ps1
```

This imports the schema, starts backend and frontend in separate PowerShell windows, and opens the browser.

## Troubleshooting

### MySQL access denied
Set `DB_USER` and `DB_PASSWORD` in the current PowerShell session, then rerun `setup-mysql.ps1`.

### Port 3306 already used
Do not change the application to a Docker port. Find the existing MySQL service and use that server, or change `DB_PORT` and the JDBC URL together.

### Port 8080/5173 already used
Stop the process using that port, or change the corresponding Spring Boot/Vite port and frontend API URL consistently.

### Frontend says backend is unavailable
First verify `http://localhost:8080/api/health`. The frontend should not be debugged until the backend health endpoint returns HTTP 200.
