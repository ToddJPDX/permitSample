# Permit Lookup — Lightweight exercise

This is a little app that makes a local UI with a html file, a Java file, and a little database. The main purpose is to practice Java with a lightwieght application. Using frameworks, REST apis, and small data layer(h2 for SQL).

How to run:
## 1. Check you have a JDK (not just a JRE)

```bash
javac -version
```

If that fails, install one (single package, no build tooling):

```bashP
sudo apt update
sudo apt install openjdk-17-jdk-headless -y
```

## 2. Get the H2 database jar (one-time, ~2.5MB)

This is a direct GitHub release download (not a package manager), so it's a single small file:

```bash
curl -sL -o lib/h2.jar \
  "https://github.com/h2database/h2database/releases/download/version-2.4.240/h2-2.4.240.jar"
```

## 3. Compile

```bash
javac -d out src/PermitApp.java
```

## 4. Run

```bash
java -cp "out:lib/h2.jar" PermitApp
```

You should see:
```
Database ready: 2 addresses, 3 permits loaded.
Server running: http://localhost:8080
```

## 5. Try it

Open **http://localhost:8080** in a browser — pick an address from the dropdown, click "Look up."

Or from the command line, in a second terminal:

```bash
curl "http://localhost:8080/api/permits?street=1221%20SW%204th%20Ave"
curl "http://localhost:8080/api/permits?street=999%20Nowhere%20St"     # empty array, still 200
curl -i "http://localhost:8080/api/permits"                            # 400, missing param
```

Stop the server with `Ctrl+C` in the terminal it's running in.

### The Three Key Transitions:

- **From HTML to Java (The URL is the Bridge):** The browser encodes UI selections into a standardized web address (the URL query string). The local Java backend reads that exact string to understand what the user wants.
    
- **From Java to SQL (The Connection is the Bridge):** The Java code translates the request into a database query and sends it over the JDBC database connection.
    
- **From SQL back to HTML (JSON is the Bridge):** The database gives Java raw rows. Java serializes those rows into JSON. JavaScript translates that JSON back into visual HTML elements on the UI screen.