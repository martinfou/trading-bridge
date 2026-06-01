# Historical Market Data

Do **not** commit data files to git. They are too large for the repository.

## Download

```bash
./scripts/download-data.sh --list     # voir l'état
./scripts/download-data.sh --gentle   # 1 paire × 1 an (~5s)
./scripts/download-data.sh --all      # tout downloader (~9 min)
```

## Structure

```mermaid
flowchart TB
    DATA[data/]
    DATA --> HIST[historical/ gitignored]
    HIST --> DUK[dukascopy/ CSV raw]
    HIST --> BARS[bars/ .bars binary]
    DATA --> CI[ci/ committed mini-dataset]
    DATA --> RUNTIME[runtime/ SQLite stores]
    DATA --> SQINBOX[sq-inbox/ SQ XML hot folder]
```

Les fichiers sont regénérés à la volée. Sur le VPS, l'image Docker
télécharge les données au premier démarrage.

## Source

Dukascopy via `dukascopy-node` (npm). Données gratuites et publiques.
