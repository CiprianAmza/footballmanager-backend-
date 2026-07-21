Football Manager Game Simulator

<img width="1512" height="904" alt="image" src="https://github.com/user-attachments/assets/a7a620fe-d326-44d9-a4bc-e4374df964db" />


Project Overview

A comprehensive, web-based football management simulation game designed to replicate the depth and tactical complexity of modern football management. Built with a robust Java Spring Boot backend and a dynamic Angular frontend, this application allows users to take full control of a football club, managing everything from tactical setups and squad planning to finances and scouting.

The interface features a modern "Dark Theme" inspired by industry-standard management simulations, ensuring an immersive user experience with interactive data visualizations, drag-and-drop tactical boards, and detailed analytical dashboards.

Key Features

⚽ Tactics & Match Engine

Interactive Tactics Board: Full drag-and-drop functionality to set formations and player roles on a visual pitch.

Advanced Instructions: Granular control over team mentality, possession style, passing directness, and tempo.

Squad Planner: Visual depth chart to plan the current and future squad structure.

📊 Data Hub & Analytics

Performance Analysis: SVG-based Radar Charts comparing team performance against league averages.

Key Findings: Automated insights into attacking and defensive efficiency (xG, pass completion, etc.).

Match Reports: Detailed post-match breakdowns and opponent analysis.

🌍 Club & Competition Management

Dynamic Competitions: specialized views for League Tables (standings, form guides) and Cup Tournaments (knockout brackets).

Club Overview: Detailed profiles including stadium info, rivalries, kits, and club history/honours.

Finances: Interactive charts tracking income, expenditure, and wage budgets over time.

🔎 Scouting & Player Development

Advanced Scouting Network: Searchable database with complex filtering (age, salary, attributes, potential).

Player Profiles: Comprehensive views for attributes, contract details, career history, and trophy cabinets.

Development Center: Track loan progress with visual form guides and manage youth prospects.

Medical Centre: Risk assessment dashboard monitoring match load, fatigue, and injury susceptibility.

Tech Stack

Backend

Java Spring Boot: RESTful API architecture.

Hibernate / JPA: Data persistence and object-relational mapping.

Jackson: Advanced JSON processing for complex tactical data storage.

H2 Database: Lightweight, in-memory database for development and testing.

Frontend

Angular: Component-based SPA architecture.

TypeScript: Strongly typed logic for complex game mechanics.

CSS Grid & Flexbox: Responsive, complex layouts mimicking desktop software interfaces.

SVG & Canvas: Custom-built charts and tactical visualizations without heavy external libraries.

This project is a personal educational initiative inspired by the Football Manager series.

<img width="1512" height="857" alt="image" src="https://github.com/user-attachments/assets/5b737987-ad1c-4699-973c-d37c3b81ac5c" />

## Development setup

The application is split across two GitHub repositories:

- Backend: `https://github.com/CiprianAmza/footballmanager-backend-.git`
- Frontend: `https://github.com/CiprianAmza/footballmanager-frontend-test.git`

### Requirements

- Java 17
- Maven 3.9+
- Node.js 20 and npm 10
- Git LFS (required for the bundled save game)

On macOS, install and initialise Git LFS before cloning:

```bash
brew install git-lfs
git lfs install
```

Clone both projects:

```bash
mkdir -p ~/IdeaProjects
cd ~/IdeaProjects
git clone https://github.com/CiprianAmza/footballmanager-backend-.git
git clone https://github.com/CiprianAmza/footballmanager-frontend-test.git
```

If the backend was cloned before Git LFS was installed, download the save with:

```bash
cd ~/IdeaProjects/footballmanager-backend-
git lfs pull
```

Start the backend:

```bash
cd ~/IdeaProjects/footballmanager-backend-
mvn clean install
mvn spring-boot:run
```

Start the frontend in a second terminal:

```bash
cd ~/IdeaProjects/footballmanager-frontend-test
npm ci
npm start
```

- Frontend: `http://localhost:4200`
- Backend: `http://localhost:8086`

## Bundled save game

The repository contains the current career snapshot at:

```text
saves/ciprian-sherlock-season-15-day-341.json
```

Snapshot state:

- Season 15
- 7 July (day 341, morning)
- Ciprian managing Sherlock FC
- Save format version 5

The save is stored through Git LFS because the complete match and career history
is approximately 411 MB. After starting the backend, restore it with:

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  --data-binary @saves/ciprian-sherlock-season-15-day-341.json \
  http://localhost:8086/game/import
```

A successful import returns `"success": true` together with the restored season,
date, team and manager. Refresh the frontend after the import.

The development database is H2 in-memory, so stopping the backend erases the
running database. Export a fresh snapshot before a restart when the career has
advanced:

```bash
curl http://localhost:8086/game/export -o football-manager-save.json
```

## Automated testing

Fast unit tests:

```bash
mvn test
```

Standard integration suite:

```bash
mvn verify
```

Full career simulation through the real calendar, every competition and three
season transitions:

```bash
mvn verify -Pcareer
```

Use `-Dcareer.seasons=2` for the shorter two-season variant. The accepted range
is deliberately limited to 2–3 seasons. A run writes its audit report to
`target/full-career-simulation.md`.


