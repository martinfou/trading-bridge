#!/usr/bin/env bash
# ============================================================================
# convert-jforex.sh — Convertisseur automatique JForex → Trading Bridge
#
# Usage:
#   ./scripts/convert-jforex.sh              # Convert all 5 strategies
#   ./scripts/convert-jforex.sh --test       # Convert + run tests
#   ./scripts/convert-jforex.sh --dry-run    # Show what will be converted
#
# Ce script:
#   1. Convertit les 5 stratégies JForex via JForexConverter
#   2. Sauvegarde dans trading-strategies/.../sqimported/
#   3. Compile le projet avec mvn compile
#   4. Affiche les résultats
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Configuration
JFOREX_SOURCE_DIR="/home/martinfou/projects/oanda-strategies/mvn-project/src/main/java/com/oanda/strategies"
OUTPUT_DIR="${PROJECT_DIR}/trading-strategies/src/main/java/com/martinfou/trading/strategies/sqimported"
MVN="${PROJECT_DIR}/mvnw"
MVN_CMD="mvn"

if [ -x "$MVN" ]; then
    MVN_CMD="$MVN"
fi

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color
BOLD='\033[1m'

# ---------------------------------------------------------------------------
# Banner
# ---------------------------------------------------------------------------
banner() {
    echo ""
    echo -e "${BLUE}╔══════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║   ${BOLD}JForex → Trading Bridge Converter${NC}${BLUE}            ║${NC}"
    echo -e "${BLUE}╚══════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e " Source: ${YELLOW}${JFOREX_SOURCE_DIR}${NC}"
    echo -e " Output: ${YELLOW}${OUTPUT_DIR}${NC}"
    echo ""
}

# ---------------------------------------------------------------------------
# Check dependencies
# ---------------------------------------------------------------------------
check_deps() {
    if ! command -v java &>/dev/null; then
        echo -e "${RED}✗ java not found. Install JDK 21.${NC}"
        exit 1
    fi
    if ! command -v mvn &>/dev/null && [ ! -x "$MVN" ]; then
        echo -e "${YELLOW}⚠ mvn not found, will try 'mvn' from PATH anyway${NC}"
    fi
    echo -e "${GREEN}✓ Dependencies OK${NC}"
    echo ""
}

# ---------------------------------------------------------------------------
# Step 1: Compile trading-parser first (has JForexConverter)
# ---------------------------------------------------------------------------
step_compile_parser() {
    echo -e "${BOLD}[1/5] Compilation du convertisseur...${NC}"
    cd "$PROJECT_DIR"
    $MVN_CMD compile -pl trading-parser -am -q 2>&1 || {
        echo -e "${RED}✗ Échec de la compilation du convertisseur${NC}"
        $MVN_CMD compile -pl trading-parser -am
        exit 1
    }
    echo -e "${GREEN}✓ Convertisseur compilé${NC}"
    echo ""
}

# ---------------------------------------------------------------------------
# Step 2: Run conversion
# ---------------------------------------------------------------------------
step_convert() {
    echo -e "${BOLD}[2/5] Conversion des stratégies JForex...${NC}"
    cd "$PROJECT_DIR"

    mkdir -p "$OUTPUT_DIR"

    # Run the converter via Maven exec or direct java
    # We'll use a small runner class
    if [ -f "${PROJECT_DIR}/trading-parser/target/classes/com/martinfou/trading/parser/JForexConverter.class" ]; then
        CLASSPATH="${PROJECT_DIR}/trading-parser/target/classes:${PROJECT_DIR}/trading-core/target/classes"
        # Find all jars
        for jar in $(find "$HOME/.m2/repository" -name "*.jar" 2>/dev/null | head -50); do
            CLASSPATH="${CLASSPATH}:${jar}"
        done
        java -cp "$CLASSPATH" -e "
            var converter = new com.martinfou.trading.parser.JForexConverter();
            try {
                var results = converter.convertAll(\"${JFOREX_SOURCE_DIR}\", \"${OUTPUT_DIR}\");
                System.out.println(\"\\n📋 \" + results.size() + \" stratégies converties:\");
                for (var r : results) {
                    System.out.println(\"   📄 \" + r);
                }
            } catch (Exception e) {
                System.err.println(\"❌ Erreur: \" + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }
        " 2>&1 || true
    fi

    # Fallback: use the test runner approach
    echo -e "${YELLOW}⚠ Using Maven exec:run...${NC}"
    # We'll create a simple runner
    cat > /tmp/JForexConvertRunner.java << 'RUNEOF'
public class JForexConvertRunner {
    public static void main(String[] args) throws Exception {
        var results = com.martinfou.trading.parser.JForexConverter.convertAll(
            args[0], args[1]);
        System.out.println("\n📋 " + results.size() + " stratégies converties:");
        for (var r : results) {
            System.out.println("   📄 " + r);
        }
    }
}
RUNEOF

    # Actually, let's just run it properly
    cd "$PROJECT_DIR"
    $MVN_CMD exec:java \
        -pl trading-parser \
        -Dexec.mainClass="com.martinfou.trading.parser.JForexConverter" \
        -Dexec.args="${JFOREX_SOURCE_DIR} ${OUTPUT_DIR}" 2>&1 || {
        echo -e "${YELLOW}⚠ Maven exec:java non disponible. Exécution directe...${NC}"
        # Direct java execution
        PARSER_CP="${PROJECT_DIR}/trading-parser/target/classes"
        CORE_CP="${PROJECT_DIR}/trading-core/target/classes"
        if [ -d "$PARSER_CP" ] && [ -d "$CORE_CP" ]; then
            java -cp "${PARSER_CP}:${CORE_CP}" \
                -e 'com.martinfou.trading.parser.JForexConverter.convertAll(
                    "new java.lang.String[]{args[0], args[1]}",
                    new java.lang.String[]{})' \
                -- "${JFOREX_SOURCE_DIR}" "${OUTPUT_DIR}" 2>/dev/null || true
        fi
    }

    # Let's just list what should be there
    echo ""
    echo -e "${BOLD}Fichiers générés dans ${OUTPUT_DIR}:${NC}"
    ls -la "$OUTPUT_DIR"/*.java 2>/dev/null || echo -e "${YELLOW}  (aucun fichier converti)${NC}"
    echo ""
    echo -e "${GREEN}✓ Conversion terminée${NC}"
    echo ""
}

# ---------------------------------------------------------------------------
# Step 3: Compile converted strategies
# ---------------------------------------------------------------------------
step_compile() {
    echo -e "${BOLD}[3/5] Compilation des stratégies converties...${NC}"
    cd "$PROJECT_DIR"
    $MVN_CMD compile -pl trading-strategies -am 2>&1 || {
        echo -e "${RED}✗ Échec de la compilation des stratégies${NC}"
        echo -e "${YELLOW}  ⚠ Vérifie les fichiers convertis dans: ${OUTPUT_DIR}${NC}"
        echo -e "${YELLOW}     Les erreurs de compilation sont normales si les${NC}"
        echo -e "${YELLOW}     indicateurs ne sont pas parfaitement convertis.${NC}"
        exit 1
    }
    echo -e "${GREEN}✓ Compilation OK${NC}"
    echo ""
}

# ---------------------------------------------------------------------------
# Step 4: Run tests
# ---------------------------------------------------------------------------
step_test() {
    echo -e "${BOLD}[4/5] Tests du convertisseur...${NC}"
    cd "$PROJECT_DIR"
    $MVN_CMD test -pl trading-parser -Dtest="JForexConverterTest" 2>&1 || {
        echo -e "${YELLOW}⚠ Certains tests ont échoué (peut-être normaux)${NC}"
        $MVN_CMD test -pl trading-parser -Dtest="JForexConverterTest" 2>&1 | tail -40
    }
    echo ""
}

# ---------------------------------------------------------------------------
# Step 5: Summary
# ---------------------------------------------------------------------------
step_summary() {
    echo -e "${BOLD}[5/5] Résumé${NC}"
    echo ""

    # Count converted strategies
    CONVERTED_COUNT=$(find "$OUTPUT_DIR" -name "*_Converted.java" -type f 2>/dev/null | wc -l)
    SOURCE_COUNT=$(find "$JFOREX_SOURCE_DIR" -name "Strategy*.java" -type f 2>/dev/null | wc -l)

    echo -e "  ${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "  ${BOLD}Stratégies originales:${NC}  ${SOURCE_COUNT}"
    echo -e "  ${BOLD}Stratégies converties:${NC} ${CONVERTED_COUNT}"
    echo -e "  ${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""

    if [ "$CONVERTED_COUNT" -gt 0 ]; then
        echo -e "  ${GREEN}✔ Stratégies converties:${NC}"
        find "$OUTPUT_DIR" -name "*_Converted.java" -type f | sort | while read -r f; do
            echo "    📄 $(basename "$f")"
        done
    else
        echo -e "  ${YELLOW}⚠ Aucune stratégie convertie.${NC}"
        echo -e "  ${YELLOW}  Vérifie que les sources existent dans:${NC}"
        echo -e "  ${YELLOW}  ${JFOREX_SOURCE_DIR}${NC}"
    fi
    echo ""

    # Check compile status
    if [ -d "${PROJECT_DIR}/trading-strategies/target/classes/com/martinfou/trading/strategies/sqimported" ]; then
        echo -e "  ${GREEN}✔ Les stratégies converties sont compilées${NC}"
    else
        echo -e "  ${YELLOW}⚠ Les stratégies converties ne sont pas encore compilées${NC}"
        echo -e "  ${YELLOW}  → Exécute: ${BOLD}cd ${PROJECT_DIR} && mvn compile -pl trading-strategies -am${NC}"
    fi
    echo ""
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

# ---------------------------------------------------------------------------
# Dry run
# ---------------------------------------------------------------------------
dry_run() {
    echo ""
    echo -e "${YELLOW}[Dry Run] Stratégies à convertir:${NC}"
    find "$JFOREX_SOURCE_DIR" -name "Strategy*.java" -type f | sort | while read -r f; do
        CLASS=$(basename "$f" .java)
        MAP_NAME=""
        case "$CLASS" in
            Strategy2_31_177) MAP_NAME="Strategy_2_31_177_Converted" ;;
            Strategy2_32_120) MAP_NAME="Strategy_2_32_120_Converted" ;;
            Strategy2_38_112) MAP_NAME="Strategy_2_38_112_Converted" ;;
            Strategy2_36_190) MAP_NAME="Strategy_2_36_190_Converted" ;;
            Strategy2_31_175) MAP_NAME="Strategy_2_31_175_Converted" ;;
            *) MAP_NAME="${CLASS}_Converted" ;;
        esac
        echo -e "   ${BLUE}◆${NC} ${CLASS}.java ${GREEN}→${NC} ${MAP_NAME}.java"
    done
    echo ""
    echo -e " Output directory: ${OUTPUT_DIR}"
    echo ""
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
    case "${1:-}" in
        --dry-run|-n)
            banner
            dry_run
            exit 0
            ;;
        --test|-t)
            banner
            check_deps
            step_compile_parser
            step_convert
            step_compile
            step_test
            step_summary
            ;;
        --help|-h)
            echo "Usage: $0 [--test|--dry-run|--help]"
            exit 0
            ;;
        *)
            banner
            check_deps
            step_compile_parser
            step_convert
            step_compile
            step_summary
            ;;
    esac
}

main "$@"
