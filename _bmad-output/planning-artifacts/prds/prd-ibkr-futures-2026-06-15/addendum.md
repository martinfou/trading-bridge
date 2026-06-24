# Addendum : Spécifications techniques et Intégration de l'API TWS Interactive Brokers

Ce document regroupe les détails techniques d'implémentation et de connectivité avec l'API Java native d'Interactive Brokers (TWS API) pour le support des contrats Futures (FUT), en particulier pour le Micro E-mini S&P 500 (MES).

---

## 1. Définition des Contrats Futures (FUT) dans l'API IBKR

Dans l'API TWS Java d'IBKR, les contrats sont modélisés par la classe `com.ib.client.Contract`. Pour lever toute ambiguïté (par exemple pour différencier le standard E-mini ($50/point) du Micro E-mini ($5/point)), il est indispensable de spécifier explicitement les champs ci-dessous :

### Paramètres de Contrat requis pour le MES :
*   **`secType`** : Doit être défini à `"FUT"`.
*   **`symbol`** : `"MES"` (symbole de base du sous-jacent).
*   **`exchange`** : `"CME"` (les contrats Futures ne supportent pas le routage `SMART` d'IBKR, l'échange de destination doit être explicite).
*   **`currency`** : `"USD"`.
*   **`multiplier`** : `"5"` (le multiplicateur de point pour le MES).
*   **`lastTradeDateOrContractMonth`** : Permet de définir l'échéance du contrat. Formats acceptés :
    *   `YYYYMM` (ex: `"202609"` pour l'échéance de Septembre 2026). TWS résout automatiquement vers le contrat par défaut de ce mois.
    *   `YYYYMMDD` (ex: `"20260918"` pour spécifier le dernier jour de trading exact).

### Exemple de construction en Java :
```java
Contract contract = new Contract();
contract.symbol("MES");
contract.secType("FUT");
contract.exchange("CME");
contract.currency("USD");
contract.lastTradeDateOrContractMonth("202609");
contract.multiplier("5");
```

---

## 1.2 Ingestion des Données Historiques via `reqHistoricalData`

Pour récupérer les bougies de backtest directement depuis IBKR, l'application utilise l'API `reqHistoricalData`.

### Requête de bougies historiques :
```java
// Dans IbkrGatewayClient
int reqId = 3001; // ID unique de requête
String endDateTime = "20260615 23:59:59 UTC"; // Date de fin
String durationStr = "1 Y"; // Période demandée (ex: 1 an)
String barSizeSetting = "1 hour"; // Résolution (ex: 1 heure)
String whatToShow = "TRADES"; // TRADES pour les futures
int useRTH = 0; // 0 = inclure hors session RTH
int formatDate = 1; // 1 = format de date texte
boolean keepUpToDate = false; // false = historique figé

clientSocket.reqHistoricalData(reqId, contract, endDateTime, durationStr, barSizeSetting, whatToShow, useRTH, formatDate, keepUpToDate, null);
```

### Callbacks de réception dans `EWrapper` :
Chaque bougie est retournée via le callback `historicalData` et se termine par `historicalDataEnd` :
```java
@Override
public void historicalData(int reqId, Bar bar) {
    System.out.printf("[BAR] Time: %s | Open: %.2f | High: %.2f | Low: %.2f | Close: %.2f | Volume: %s%n",
        bar.time(), bar.open(), bar.high(), bar.low(), bar.close(), bar.volume().toString());
    // Convertir l'objet Bar en Candle locale et stocker
}

@Override
public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
    System.out.printf("[INGESTION DONE] ReqId: %d | Plage: %s à %s%n", reqId, startDateStr, endDateStr);
}
```

---

## 2. Modèle de Connexion et Threading Asynchrone

L'API TWS fonctionne sur un modèle asynchrone basé sur des sockets TCP. Pour recevoir les messages émis par la TWS ou l'IB Gateway, l'application doit démarrer un thread d'écoute (`EReader`) en tâche de fond qui dépile les messages et invoque les callbacks de l'interface `EWrapper`.

### Architecture du Manager de Connexion :
```java
import com.ib.client.*;

public class IbkrConnectionManager {
    private EClientSocket clientSocket;
    private EJavaSignal signal = new EJavaSignal();
    private MyWrapper wrapper = new MyWrapper();

    public void initConnection(String host, int port, int clientId) {
        clientSocket = new EClientSocket(wrapper, signal);
        clientSocket.eConnect(host, port, clientId);

        // Lancement du Reader en tâche de fond
        EReader reader = new EReader(clientSocket, signal);
        reader.start();

        new Thread(() -> {
            while (clientSocket.isConnected()) {
                signal.waitForSignal();
                try {
                    reader.processMsgs();
                } catch (Exception e) {
                    System.err.println("Erreur de traitement des messages API : " + e.getMessage());
                }
            }
        }).start();
    }
}
```

---

## 3. Mécanisme de Soumission des Ordres et Suivi des Identifiants

1. **Ordres au marché (MARKET)** :
   ```java
   public void submitMarketOrder(int orderId, Contract contract, String action, double quantity) {
       Order order = new Order();
       order.action(action);                      // "BUY" ou "SELL"
       order.orderType("MKT");                    // Ordre au marché
       order.totalQuantity(Decimal.get(quantity)); // API IBKR moderne (Decimal)
       
       clientSocket.placeOrder(orderId, contract, order);
   }
   ```
2. **Gestion des Identifiants d'Ordre** :
   * Avant de placer un ordre, l'API requiert un ID unique incrémental.
   * Cet ID est initialisé par le callback `EWrapper.nextValidId(int orderId)` lors de la connexion initiale.
   * L'application doit maintenir cet ID en mémoire de façon thread-safe et l'incrémenter pour chaque nouvel ordre.

---

## 4. Réconciliation Asynchrone des Fills et Commissions

Les exécutions (fills) et les rapports de frais (commissions) arrivent par deux callbacks distincts dans l'interface `EWrapper`. Ils doivent être corrélés à l'aide de l'identifiant d'exécution unique d'IBKR (`execId`).

### Processus de capture :
1. **Événement d'exécution (`execDetails`)** :
   Invoqué lorsqu'une transaction est exécutée. Fournit l'ID d'exécution `execId` (ex: `E-12345`), le prix moyen d'exécution et la quantité exécutée.
2. **Événement de commission (`commissionReport`)** :
   Invoqué peu de temps après l'exécution. Fournit la commission réelle prélevée par le courtier et le PnL réalisé. Ce message contient le même `execId` que celui fourni par `execDetails`.

### Exemple de Callback dans `EWrapper` :
```java
public class MyWrapper implements EWrapper {
    
    @Override
    public void execDetails(int reqId, Contract contract, Execution execution) {
        String execId = execution.execId();
        int orderId = execution.orderId();
        double fillPrice = execution.price();
        Decimal fillQty = execution.shares();
        
        System.out.printf("[FILL] OrderId: %d | ExecId: %s | Symbol: %s | Qty: %s | Price: %.2f%n",
            orderId, execId, contract.localSymbol(), fillQty.toString(), fillPrice);
        
        // Stocker temporairement l'exécution pour attendre le CommissionReport associé.
    }

    @Override
    public void commissionReport(CommissionReport commissionReport) {
        String execId = commissionReport.execId(); // Clé de corrélation
        double commission = commissionReport.commission();
        String currency = commissionReport.currency();
        double realizedPnL = commissionReport.realizedPNL();

        System.out.printf("[COMMISSION] ExecId: %s | Commission: %.2f %s | Realized PnL: %.2f%n",
            execId, commission, currency, realizedPnL);
        
        // Mettre à jour l'enregistrement d'exécution dans la base de données locale.
    }
    
    // ... autres callbacks EWrapper requis ...
}
```

---

## 5. Récupération des Métriques de Marge du Compte via TWS API

Pour afficher en temps réel les indicateurs de marge (Marge Initiale, Marge de Maintenance, Buying Power, Equity) requis par le Dashboard, le connecteur utilise le mécanisme d'abonnement au résumé de compte d'IBKR.

### Requête d'abonnement (`reqAccountSummary`) :
Pour obtenir les métriques de marge globales du compte, nous appelons `reqAccountSummary` avec des balises de paramètres spécifiques :
```java
// Dans IbkrGatewayClient
int reqId = 2001; // ID unique de requête
clientSocket.reqAccountSummary(reqId, "All", "NetLiquidation,Equity,InitMarginReq,MaintMarginReq,BuyingPower");
```

### Callbacks de réception dans `EWrapper` :
Les données de compte sont reçues de manière asynchrone via le callback `accountSummary` et se terminent par `accountSummaryEnd` :
```java
@Override
public void accountSummary(int reqId, String account, String tag, String value, String currency) {
    System.out.printf("[ACCOUNT] Tag: %s | Value: %s | Currency: %s%n", tag, value, currency);
    // Mapper les valeurs :
    // - "NetLiquidation" -> balance / liquidité
    // - "Equity" -> fonds propres
    // - "InitMarginReq" -> exigence de marge initiale actuelle
    // - "MaintMarginReq" -> exigence de marge de maintenance actuelle
    // - "BuyingPower" -> marge disponible pour ouvrir de nouvelles positions
}

@Override
public void accountSummaryEnd(int reqId) {
    System.out.println("Fin de la réception du résumé de compte.");
}
```

---

## 6. Contrats d'API (Communication Frontend - Backend)

Pour l'IHM Vue 3/Electron, les nouvelles fonctionnalités nécessitent les endpoints de communication REST suivants exposés par le module `trading-runtime` (classe `ControlPlaneMain` / contrôleurs HTTP) :

### 6.1 Lecture des informations de compte Futures
* **Endpoint** : `GET /api/ibkr/account-summary`
* **Description** : Retourne l'état consolidé du compte avec ses exigences de marge.
* **Payload de réponse (JSON)** :
  ```json
  {
    "accountId": "U1234567",
    "currency": "USD",
    "balance": 102450.00,
    "equity": 102450.00,
    "initMarginReq": 1500.00,
    "maintMarginReq": 1200.00,
    "buyingPower": 100950.00
  }
  ```

### 6.2 Extension du Payload de Backtest
Le formulaire de configuration de backtest de l'IHM doit transmettre de nouveaux paramètres pour les Futures dans le payload de requête de simulation.
* **Endpoint** : `POST /api/backtest/run`
* **Modifications du Payload (JSON)** :
  ```json
  {
    "strategyId": "MES_Breakout_v1",
    "symbol": "MES",
    "startDate": "2023-01-01T00:00:00Z",
    "endDate": "2026-01-01T00:00:00Z",
    "initialCapital": 100000,
    "parameters": {
      "futuresMode": true,
      "multiplier": 5.0,
      "initialMargin": 1500.0,
      "maintenanceMargin": 1200.0,
      "rolloverDaysBeforeExpiry": 10,
      "commissionPerContract": 0.87
    }
  }
  ```

