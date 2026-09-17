# GitHub Publishing Guide

Do not share a GitHub password, recovery code, or personal access token in chat.

## Recommended method: GitHub Desktop

1. Install GitHub Desktop.
2. Sign in to your GitHub account.
3. Add this folder as an existing local repository.
4. Create a repository named `PAYMESH-distributed-payment-gateway`.
5. Keep the repository **public** only if your college submission permits public source code.
6. Commit all files with a message such as `final: PAYMESH distributed payment gateway`.
7. Push origin.

## Command-line method

From the project root:

```powershell
git init
git add .
git commit -m "final: PAYMESH distributed payment gateway"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/PAYMESH-distributed-payment-gateway.git
git push -u origin main
```

Git will authenticate through your configured Git Credential Manager/browser flow. Do not put credentials inside the remote URL.
