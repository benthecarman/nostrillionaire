CREATE TABLE rounds
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    number       INTEGER                           NOT NULL,
    start_date   INTEGER                           NOT NULL,
    end_date     INTEGER                           NOT NULL,
    num_zaps     INTEGER,
    total_zapped INTEGER,
    prize        INTEGER,
    profit       INTEGER,
    winner       TEXT
);

create index rounds_start_date on rounds (start_date);
create index rounds_end_date on rounds (end_date);

CREATE TABLE zaps
(
    r_hash  TEXT PRIMARY KEY NOT NULL,
    round   INTEGER          NOT NULL,
    invoice TEXT UNIQUE      NOT NULL,
    payer   TEXT             NOT NULL,
    amount  INTEGER          NOT NULL,
    request TEXT             NOT NULL,
    date    INTEGER          NOT NULL,
    note_id TEXT UNIQUE,
    FOREIGN KEY (round) REFERENCES rounds (id)
);

create index zaps_round on zaps (round);
create index zaps_amount on zaps (amount);
create index zaps_date on zaps (date);

CREATE TABLE payouts
(
    round    INTEGER PRIMARY KEY NOT NULL,
    invoice  TEXT UNIQUE         NOT NULL,
    amount   INTEGER             NOT NULL,
    fee      INTEGER             NOT NULL,
    preimage TEXT UNIQUE         NOT NULL,
    date     INTEGER             NOT NULL,
    FOREIGN KEY (round) REFERENCES rounds (id)
);

create index payouts_invoice on payouts (invoice);
create index payouts_date on payouts (date);
