#!/bin/bash
# Upload a file to Joplin as a resource and create a note with it.
#
# Usage: upload-to-joplin.sh <file_path> <note_title> [notebook_name]
#
# Requirements: curl, python3, Joplin API on localhost:41184

set -e

FILE_PATH="$1"
NOTE_TITLE="$2"
NOTEBOOK_NAME="${3:-03 - Resources / 🔗 Reference}"

if [ -z "$FILE_PATH" ] || [ -z "$NOTE_TITLE" ]; then
    echo "Usage: $0 <file_path> <note_title> [notebook_name]"
    exit 1
fi
if [ ! -f "$FILE_PATH" ]; then
    echo "❌ File not found: $FILE_PATH"
    exit 1
fi

# Joplin API token from running MCP server
JOPLIN_TOKEN=$(tr '\0' '\n' < /proc/$(pgrep -f "joplin-mcp-server" | head -1)/environ 2>/dev/null \
    | grep JOPLIN_TOKEN | cut -d= -f2)
if [ -z "$JOPLIN_TOKEN" ]; then
    echo "❌ Could not find Joplin API token"
    exit 1
fi

API="http://localhost:41184"
FILENAME=$(basename "$FILE_PATH")

echo "📎 Uploading $FILENAME to Joplin..."

# Step 1: Upload file as a resource
RESPONSE=$(curl -s "$API/resources?token=$JOPLIN_TOKEN" \
    -F "data=@$FILE_PATH" \
    -F "props={\"title\":\"$FILENAME\"}")
RESOURCE_ID=$(echo "$RESPONSE" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)

if [ -z "$RESOURCE_ID" ]; then
    echo "❌ Resource upload failed: $RESPONSE"
    exit 1
fi
echo "✅ Resource: $RESOURCE_ID ($FILENAME)"

# Step 2: Find notebook ID
LEAF_NAME=$(echo "$NOTEBOOK_NAME" | awk -F'/' '{print $NF}' | sed 's/^ *//')
NOTEBOOK_ID=$(curl -s "$API/folders?token=$JOPLIN_TOKEN&limit=100" \
    | python3 -c "
import json,sys
data = json.load(sys.stdin)
for item in data.get('items', []):
    if item.get('title') == '$LEAF_NAME':
        print(item.get('id'))
        break
" 2>/dev/null)

if [ -z "$NOTEBOOK_ID" ]; then
    echo "⚠️  Notebook '$LEAF_NAME' not found, using default"
fi

# Get resource info for the markdown link
MIME_TYPE=$(echo "$RESPONSE" | python3 -c "import json,sys; print(json.load(sys.stdin).get('mime','application/pdf'))" 2>/dev/null)

# Build note body
if echo "$MIME_TYPE" | grep -q "^image/"; then
    RESOURCE_LINK="![](:/$RESOURCE_ID)"
else
    RESOURCE_LINK="[📥 Télécharger le rapport PDF](:/$RESOURCE_ID)"
fi

python3 -c "
import json, sys

body = '''# $NOTE_TITLE

## Fichier Attaché

$RESOURCE_LINK

---

*Rapport généré automatiquement par Trading Bridge Backtest Engine*
'''

data = {
    'title': '$NOTE_TITLE',
    'body': body.strip(),
}
if '$NOTEBOOK_ID':
    data['parent_id'] = '$NOTEBOOK_ID'

print(json.dumps(data))
" | curl -s "$API/notes?token=$JOPLIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d @- > /dev/null

echo "✅ Note '$NOTE_TITLE' créée dans '$NOTEBOOK_NAME' avec PDF attaché"
echo "📓 Joplin: $NOTE_TITLE"
