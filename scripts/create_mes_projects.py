import os
import shutil
import zipfile
import re

SQ_HOME = "/Volumes/T7/Applications/StrategyQuantXB141.app/Contents/Resources"
TEMPLATE_DIR = "/Volumes/T7/src/trading-bridge/target/sqx_mes_project"

def create_project(project_name, market_side):
    project_dir = os.path.join(SQ_HOME, "user/projects", project_name)
    os.makedirs(project_dir, exist_ok=True)
    os.makedirs(os.path.join(project_dir, "log"), exist_ok=True)

    databanks = [
        "Results", "Initial population", "Last generation",
        "Strategies to improve", "Final strategies", "Existing portfolio"
    ]
    for db in databanks:
        os.makedirs(os.path.join(project_dir, "databanks", db), exist_ok=True)

    # 1. Update config.xml
    with open(os.path.join(TEMPLATE_DIR, "config.xml"), "r", encoding="utf-8") as f:
        config_xml = f.read()

    config_xml = re.sub(r'<Project name="[^"]*"', f'<Project name="{project_name}"', config_xml)

    # 2. Update Build-Task1.xml
    with open(os.path.join(TEMPLATE_DIR, "Build-Task1.xml"), "r", encoding="utf-8") as f:
        build_xml = f.read()

    # Set market side (long or short)
    build_xml = re.sub(
        r'<MarketSides type="[^"]*">',
        f'<MarketSides type="{market_side}">',
        build_xml
    )

    # Update date range to 2014.01.01 - 2026.01.20
    build_xml = re.sub(r'dateTo="2022\.06\.30"', 'dateTo="2026.01.20"', build_xml)

    # Update ProfitFactor filter to > 1.60
    # In Conditions: <Column-Value column="ProfitFactor" .../> ... <Numeric-Value value="1.3" />
    build_xml = build_xml.replace(
        '<Column-Value column="ProfitFactor" columnType="0" format="Decimal2" resultType="main" direction="0" sampleType="10" plType="10" confidenceLevel="50" market="1" subresult="30" pctRatio="0" class="ProfitFactor" />',
        '<Column-Value column="ProfitFactor" columnType="0" format="Decimal2" resultType="main" direction="0" sampleType="10" plType="10" confidenceLevel="50" market="1" subresult="30" pctRatio="0" class="ProfitFactor" />'
    )

    # Write out modified files into a temp staging folder and zip to project.cfx
    staging_dir = f"/Volumes/T7/src/trading-bridge/target/{market_side}_staging"
    os.makedirs(staging_dir, exist_ok=True)

    for item in os.listdir(TEMPLATE_DIR):
        src = os.path.join(TEMPLATE_DIR, item)
        dst = os.path.join(staging_dir, item)
        if item == "config.xml":
            with open(dst, "w", encoding="utf-8") as out:
                out.write(config_xml)
        elif item == "Build-Task1.xml":
            with open(dst, "w", encoding="utf-8") as out:
                out.write(build_xml)
        else:
            shutil.copy2(src, dst)

    cfx_path = os.path.join(project_dir, "project.cfx")
    with zipfile.ZipFile(cfx_path, "w", zipfile.ZIP_DEFLATED) as zipf:
        for root, dirs, files in os.walk(staging_dir):
            for file in files:
                full_path = os.path.join(root, file)
                rel_path = os.path.relpath(full_path, staging_dir)
                zipf.write(full_path, rel_path)

    print(f"Created project: {project_name} -> {cfx_path} (MarketSide: {market_side})")

if __name__ == "__main__":
    create_project("MES H1 LONG - ZeroMartingale", "long")
    create_project("MES H1 SHORT - ZeroMartingale", "short")
