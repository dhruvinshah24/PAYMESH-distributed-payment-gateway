.PHONY: up down logs reset backend frontend package
up:
	docker compose up -d

down:
	docker compose down

logs:
	docker compose logs -f --tail=200

reset:
	docker compose down -v
	docker compose up -d

backend:
	cd backend && mvn spring-boot:run

frontend:
	cd frontend && npm install && npm run dev

package:
	zip -r PAYMESH-submission.zip backend database demos docs frontend docker-compose.yml Makefile README.md .gitignore
