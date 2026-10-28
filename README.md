# SQL-DB

A relational database engine written from scratch in Java, with no external dependencies. It stores data in a single file using a page-based B+ tree, keeps commits safe with a write-ahead log (WAL), and accepts a small SQL dialect from an interactive shell or over TCP from several clients at once.

I built it to learn how databases like SQLite and PostgreSQL work under the hood: storage, indexing, query parsing and planning, transactions, crash recovery and concurrency.

## Features

- **Storage engine:** a single database file made of fixed 4 KB pages, managed by a pager with a free list so deleted pages get reused.
- **B+ tree tables:** each table is a B+ tree keyed on its first column. Leaves are slotted pages that hold variable-length records. Nodes split on insert and rebalance or merge on delete.
- **Secondary indexes:** `CREATE INDEX` builds a separate B+ tree on any non-key column. The planner uses it for equality and range lookups.
- **SQL front end:** a hand-written lexer, a recursive-descent parser that builds an AST, and a binder that checks names and types before anything runs. Syntax errors report the column they occurred at.
- **Query planner:** works out key and index ranges from `WHERE` clauses, picks between a full scan, a key range and an index range, and spots conditions that can never match. `.plan` shows the last plan it chose.
- **Transactions:** `BEGIN`, `COMMIT` and `ROLLBACK`. A statement run outside a transaction commits on its own.
- **Write-ahead log with crash recovery:** committed pages go to a `-wal` file with CRC32 checksums before they reach the main file. On startup, committed transactions are replayed and torn or uncommitted ones are thrown away. Built-in crash points let you test this yourself.
- **Concurrency:** a TCP server with one thread per client. Readers see snapshots (multi-versioned pages), so they never block. Writers take a single writer lock, with a lock timeout and an idle-transaction timeout.
- **Debugging commands:** look at B+ tree structure, check every tree and index for consistency, and see page and version statistics.

## Requirements

- **Java 17 or newer** (JDK, not just the JRE). The code uses records, sealed interfaces and pattern matching.
- That's it. No build tool or libraries are needed.

Check your version:

```bash
java -version
javac -version
```

If you need a JDK, install one from [Adoptium](https://adoptium.net/) or a package manager:

```bash
# macOS (Homebrew)
brew install openjdk@21

# Ubuntu / Debian
sudo apt install openjdk-21-jdk
```

## Setup

1. **Clone the repository**

   ```bash
   git clone https://github.com/anirudhkm-cs/SQL-DB.git
   cd SQL-DB
   ```

2. **Compile**

   ```bash
   javac -d out $(find src -name "*.java")
   ```

   This puts the compiled classes in `out/`.

   On Windows (PowerShell):

   ```powershell
   javac -d out (Get-ChildItem -Recurse src -Filter *.java).FullName
   ```

3. **Start the shell**

   ```bash
   java -cp out db.Main mydata.db
   ```

   If `mydata.db` doesn't exist, it is created, along with a `mydata.db-wal` log file next to it. You should see the `db >` prompt.

## Usage

### Interactive shell

```text
$ java -cp out db.Main mydata.db
db > CREATE TABLE users (id INTEGER, name TEXT(32), age INTEGER)
Executed
db > INSERT INTO users VALUES (1, 'alice', 30)
Executed
db > INSERT INTO users VALUES (2, 'bob', 25)
Executed
db > SELECT * FROM users WHERE age > 20 ORDER BY age DESC
( 1, alice, 30 )
( 2, bob, 25 )
Executed
db > .exit
```

The prompt changes to `db*>` while a transaction is open.

### Client/server mode

Start the server:

```bash
java -cp out db.Server mydata.db [port] [lock-timeout-ms] [idle-timeout-ms]
```

| Argument          | Default | Meaning |
|-------------------|---------|---------|
| `port`            | `5435`  | TCP port to listen on |
| `lock-timeout-ms` | `5000`  | How long a writer waits for another client's transaction to finish |
| `idle-timeout-ms` | `60000` | A client idle inside an open transaction for this long is rolled back and disconnected |

Then connect one or more clients from other terminals:

```bash
java -cp out db.Client [host] [port]     # defaults: localhost 5435
```

The client accepts the same SQL and dot commands as the local shell.

## Supported SQL

Keywords are case-insensitive, and a trailing `;` is optional.

```sql
-- Tables: the first column must be INTEGER and becomes the primary key
CREATE TABLE users (id INTEGER, name TEXT(32), age INTEGER)
CREATE TABLE orders (id INTEGER, user_id INTEGER, item TEXT(32))

-- Secondary index on a non-key column
CREATE INDEX users_age ON users (age)

INSERT INTO users VALUES (1, 'alice', 30)

SELECT * FROM users
SELECT name, age FROM users WHERE age >= 18 AND (name = 'alice' OR name = 'bob')
SELECT * FROM users ORDER BY age DESC LIMIT 10
SELECT COUNT(*) FROM users
SELECT users.name, orders.item FROM users JOIN orders ON users.id = orders.user_id

UPDATE users SET age = 31 WHERE id = 1
DELETE FROM users WHERE age < 18

BEGIN
COMMIT
ROLLBACK
```

- **Types:** `INTEGER` (32-bit) and `TEXT(n)`, where `n` is the maximum size in bytes.
- **Operators:** `=`, `!=` / `<>`, `<`, `<=`, `>`, `>=`, combined with `AND`, `OR` and parentheses.
- **Strings:** single-quoted. Write `''` for a literal quote inside a string, as in `'it''s'`.
- **Joins:** a single inner `JOIN ... ON a.col = b.col`.

## Meta commands

| Command | Description |
|---------|-------------|
| `.tables` | List all tables |
| `.schema <table>` | Show the `CREATE` statements for a table and its indexes |
| `.indexes [table]` | List indexes, optionally only those on one table |
| `.plan` | Show the plan used by the last query (full scan, key range or index range) |
| `.btree <table\|index>` | Print the B+ tree structure |
| `.check [table\|index]` | Check B+ tree, index and page consistency (all objects if no name is given) |
| `.pages` | Page count and free-list size |
| `.versions` | Cached pages, old snapshot versions and active readers |
| `.crash <point>` | Make the next commit crash at a given point (see below) |
| `.exit` | Quit. An uncommitted transaction is discarded |

## Testing crash recovery

`.crash` makes the process stop abruptly (exit code 42) at a chosen point during the next commit, so you can check that the WAL recovers correctly.

Crash points: `mid-frame`, `before-commit`, `after-commit`, `mid-checkpoint`, `before-truncate`.

```text
$ java -cp out db.Main mydata.db
db > BEGIN
db*> INSERT INTO users VALUES (3, 'carol', 40)
db*> .crash after-commit
db*> COMMIT
$ java -cp out db.Main mydata.db          # restart: the WAL is replayed
db > SELECT * FROM users
( 1, alice, 30 )
( 2, bob, 25 )
( 3, carol, 40 )
db > .check
...
pages: OK
```

A crash before the commit record is written (`mid-frame`, `before-commit`) loses the transaction. A crash after it (`after-commit`, `mid-checkpoint`, `before-truncate`) keeps it.

## Project structure

```text
src/db/
├── Main.java            Interactive shell
├── Server.java          Multi-client TCP server
├── Client.java          TCP client shell
├── Wire.java            Framed wire protocol (Query / Row / Message / Done / Error)
├── Session.java         Per-connection state, locking and meta commands
│
├── Lexer.java           SQL tokenizer
├── SqlParser.java       Recursive-descent parser that builds the AST
├── Ast.java             AST node types
├── Binder.java          Resolves names and types and checks limits
├── Parser.java          Lexer → parser → binder pipeline
├── Statement.java       Bound, executable statement
├── Planner.java         Chooses scan, key range or index range; runs joins
├── Executor.java        Runs statements
├── Predicate.java       Evaluates WHERE clauses
│
├── Database.java        Tables, indexes, catalog and transactions
├── Catalog.java         System catalog (jdb_catalog)
├── Schema.java, Column.java, Row.java
├── RecordCodec.java     Turns rows into bytes and back
├── BTree.java           B+ tree insert, delete, split, merge and checks
├── InternalNode.java    Internal node page layout
├── SlottedLeaf.java     Slotted leaf page layout
├── LeafNode.java, Cursor.java
├── Index.java           Secondary indexes
├── Pager.java           Page cache, free list and multi-version snapshots
├── Wal.java             Write-ahead log and recovery
└── CrashPoints.java     Fault injection for recovery tests
```

## Limitations

This is a learning project, not a production database. Some current limits:

- Only `INTEGER` and `TEXT(n)` types, and no `NULL`.
- The first column is always the primary key, and there are no other constraints.
- One writer at a time (readers run concurrently using snapshots).
- No `DROP`, `ALTER`, aggregates other than `COUNT(*)`, `GROUP BY`, multi-way joins or subqueries.
- Table and index names can be at most 64 bytes, and `CREATE` statements at most 1000 bytes.
