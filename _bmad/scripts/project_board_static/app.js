let boardData = {
    metadata: {},
    epics: [],
    stories: []
};

let selectedStoryId = null;
let selectedEpicKey = null;
let isEpicPaneCollapsed = false;
let activeStoryKey = null;
let activeInspectorTab = 'checklist'; // 'checklist' or 'details'

// API Base URL (runs on the same server)
const API_URL = '';

async function loadBoardData() {
    try {
        const response = await fetch(`${API_URL}/api/board`);
        if (!response.ok) throw new Error('Failed to fetch board data');
        boardData = await response.json();
        
        // Fetch active story key from backend trace
        try {
            const activeRes = await fetch(`${API_URL}/api/story/active`);
            if (activeRes.ok) {
                const activeData = await activeRes.json();
                activeStoryKey = activeData.active_story_key || null;
            }
        } catch (err) {
            console.error('Error fetching active story:', err);
        }
        
        updateHUD();
        renderEpics();
        renderBoard();
        
        if (selectedStoryId) {
            renderTaskInspector(selectedStoryId);
        } else if (selectedEpicKey) {
            renderEpicInspector(selectedEpicKey);
        }
    } catch (error) {
        console.error('Error loading board data:', error);
        alert('Error loading board data. Make sure the Python server is running.');
    }
}

function updateHUD() {
    const totalStories = boardData.stories.length;
    const doneStories = boardData.stories.filter(s => s.status === 'done').length;
    const completionPercentage = totalStories > 0 ? (doneStories / totalStories * 100) : 0;
    
    // Update progress ring & text
    document.getElementById('completion-text').textContent = `${completionPercentage.toFixed(1)}%`;
    document.getElementById('progress-ring-text').textContent = `${Math.round(completionPercentage)}%`;
    
    const circle = document.getElementById('progress-circle');
    const radius = circle.r.baseVal.value;
    const circumference = 2 * Math.PI * radius;
    const offset = circumference - (completionPercentage / 100) * circumference;
    circle.style.strokeDashoffset = offset;
    
    // Update metadata title
    const meta = boardData.metadata;
    document.getElementById('project-meta').textContent = 
        `Project: ${meta.project || 'trading-bridge'} | Key: ${meta.project_key || 'NOKEY'} | Updated: ${meta.last_updated || '--'}`;
        
    // Update column counters
    updateColumnCounts();
}

function updateColumnCounts() {
    const statuses = ['backlog', 'ready-for-dev', 'in-progress', 'blocked', 'review', 'done'];
    statuses.forEach(status => {
        const count = boardData.stories.filter(s => s.status === status && (!selectedEpicKey || s.epic === selectedEpicKey)).length;
        const elem = document.getElementById(`count-${status}`);
        if (elem) elem.textContent = count;
    });
}

function renderEpics() {
    const epicList = document.getElementById('epic-list');
    epicList.innerHTML = '';
    
    // Sort epics by key number
    const sortedEpics = [...boardData.epics].sort((a, b) => {
        if (a.key.includes('desktop') && !b.key.includes('desktop')) return 1;
        if (!a.key.includes('desktop') && b.key.includes('desktop')) return -1;
        const numA = parseInt(a.key.replace(/\D/g, '')) || 0;
        const numB = parseInt(b.key.replace(/\D/g, '')) || 0;
        return numA - numB;
    });

    // Add "All Stories" filter card
    const allActive = selectedEpicKey === null ? 'bg-dracula-purple/20 border-dracula-purple text-white' : 'border-white/5 text-slate-400 hover:bg-white/5 hover:text-slate-200';
    epicList.innerHTML += `
        <div onclick="selectEpic(null)" class="p-3 rounded-xl border cursor-pointer transition flex justify-between items-center ${allActive}">
            <span class="text-sm font-semibold flex items-center gap-2">
                <i class="fa-solid fa-list"></i> All Stories
            </span>
            <span class="text-xs font-mono font-bold bg-slate-800 px-2 py-0.5 rounded text-slate-300">
                ${boardData.stories.length}
            </span>
        </div>
    `;
    
    sortedEpics.forEach(epic => {
        const pct = epic.stories_total > 0 ? (epic.stories_done / epic.stories_total * 100) : (epic.status === 'done' ? 100 : 0);
        const isActive = selectedEpicKey === epic.key;
        
        const cardStyle = isActive 
            ? 'bg-dracula-purple/20 border-dracula-purple text-white' 
            : 'border-white/5 text-slate-400 hover:bg-white/5 hover:text-slate-200';
            
        const isCurrent = epic.status === 'in-progress' ? '▶ ' : '';
        
        epicList.innerHTML += `
            <div onclick="selectEpic('${epic.key}')" class="p-3 rounded-xl border cursor-pointer transition space-y-2 relative overflow-hidden ${cardStyle}">
                <div class="flex justify-between items-start">
                    <span class="text-sm font-semibold truncate pr-2">${isCurrent}${epic.name}</span>
                    <span class="text-[10px] font-bold font-mono px-2 py-0.5 rounded ${epic.status === 'done' ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20' : epic.status === 'in-progress' ? 'bg-orange-500/10 text-orange-400 border border-orange-500/20' : 'bg-slate-800 text-slate-500'}">
                        ${epic.status.toUpperCase()}
                    </span>
                </div>
                <div class="space-y-1">
                    <div class="flex justify-between text-[10px]">
                        <span>${epic.stories_done}/${epic.stories_total} Stories</span>
                        <span>${pct.toFixed(0)}%</span>
                    </div>
                    <div class="w-full bg-slate-900 rounded-full h-1">
                        <div class="bg-dracula-purple h-1 rounded-full transition-all duration-500" style="width: ${pct}%"></div>
                    </div>
                </div>
            </div>
        `;
    });
}

function selectEpic(epicKey) {
    selectedEpicKey = epicKey;
    selectedStoryId = null; // deselect story when selecting epic
    renderEpics();
    renderBoard();
    if (epicKey) {
        document.getElementById('task-pane').classList.remove('translate-x-full');
        renderEpicInspector(epicKey);
    } else {
        closeTaskPane();
    }
}

function toggleEpicPane() {
    const epicPane = document.getElementById('epic-pane');
    isEpicPaneCollapsed = !isEpicPaneCollapsed;
    
    if (isEpicPaneCollapsed) {
        epicPane.style.width = '0px';
        epicPane.style.borderRightWidth = '0px';
    } else {
        epicPane.style.width = '320px';
        epicPane.style.borderRightWidth = '1px';
    }
}

function renderBoard() {
    const statuses = ['backlog', 'ready-for-dev', 'in-progress', 'blocked', 'review', 'done'];
    statuses.forEach(status => {
        const col = document.getElementById(`col-${status}`);
        if (col) col.innerHTML = '';
    });
    
    const filteredStories = boardData.stories.filter(s => !selectedEpicKey || s.epic === selectedEpicKey);
    
    filteredStories.forEach(story => {
        const col = document.getElementById(`col-${story.status}`);
        if (!col) return;
        
        const isSelected = selectedStoryId === story.key ? 'ring-2 ring-dracula-purple ring-offset-2 ring-offset-slate-950 shadow-dracula-purple/10' : '';
        
        // Border left colors per column status
        const borderLeft = story.status === 'done' ? 'border-l-4 border-l-emerald-500' 
            : story.status === 'review' ? 'border-l-4 border-l-purple-500' 
            : story.status === 'blocked' ? 'border-l-4 border-l-amber-500' 
            : story.status === 'in-progress' ? 'border-l-4 border-l-orange-500' 
            : story.status === 'ready-for-dev' ? 'border-l-4 border-l-cyan-500' 
            : 'border-l-4 border-l-slate-600';
            
        const isPinned = activeStoryKey === story.key;
        const pinnedClass = isPinned ? 'pinned-glow' : '';
        const pinnedBadge = isPinned ? `<span class="text-rose-500" title="Active Coding Target"><i class="fa-solid fa-thumbtack"></i></span>` : '';
        
        const stalledBadge = story.stalled ? `<span class="px-1.5 py-0.5 rounded bg-amber-500/10 border border-amber-500/20 text-amber-400 font-bold text-[9px] flex items-center gap-0.5 animate-pulse"><i class="fa-solid fa-clock"></i> STALLED</span>` : '';
        const blockedBadge = story.blockers && story.blockers.length > 0 ? `<span class="px-1.5 py-0.5 rounded bg-rose-500/10 border border-rose-500/20 text-rose-400 font-bold text-[9px] flex items-center gap-0.5 animate-pulse"><i class="fa-solid fa-triangle-exclamation"></i> BLOCKED</span>` : '';
        const specBadge = story.file_exists ? `<span class="text-slate-500 text-[10px]"><i class="fa-solid fa-file-invoice"></i> Spec</span>` : `<span class="text-rose-500/50 text-[10px]" title="Specification file missing"><i class="fa-solid fa-file-circle-xmark"></i> No Spec</span>`;
        
        const pct = story.tasks_total > 0 ? Math.round(story.tasks_done / story.tasks_total * 100) : 0;
        const taskBar = story.tasks_total > 0 ? `
            <div class="space-y-1">
                <div class="flex justify-between text-[10px] text-slate-500 font-mono">
                    <span>Tasks: ${story.tasks_done}/${story.tasks_total}</span>
                    <span>${pct}%</span>
                </div>
                <div class="w-full bg-slate-900 rounded-full h-1">
                    <div class="bg-dracula-cyan h-1 rounded-full" style="width: ${pct}%"></div>
                </div>
            </div>
        ` : '';
        
        col.innerHTML += `
            <div id="card-${story.key}" draggable="true" ondragstart="drag(event)" onclick="selectStory('${story.key}')" class="p-3 bg-slate-850 rounded-xl border border-white/5 cursor-grab hover:bg-slate-800/80 transition flex flex-col justify-between space-y-3 shadow-md ${isSelected} ${pinnedClass} ${borderLeft}">
                <div class="space-y-1">
                    <div class="flex justify-between items-center">
                        <div class="flex items-center gap-1.5">
                            <span class="text-[10px] font-mono text-slate-400 font-bold">${story.key}</span>
                            ${pinnedBadge}
                        </div>
                        <div class="flex gap-1.5">
                            ${blockedBadge}
                            ${stalledBadge}
                        </div>
                    </div>
                    <h4 class="text-sm font-semibold text-white tracking-tight leading-snug line-clamp-2">${story.title}</h4>
                </div>
                
                ${taskBar}
                
                <div class="flex justify-between items-center pt-1 border-t border-white/5">
                    ${specBadge}
                    <span class="text-[10px] font-mono text-slate-500 font-bold uppercase">${story.epic ? story.epic.replace('epic-', 'EP-') : ''}</span>
                </div>
            </div>
        `;
    });
    
    updateColumnCounts();
}

function drag(ev) {
    ev.dataTransfer.setData("text", ev.target.id);
    ev.target.classList.add('opacity-50');
}

function allowDrop(ev) {
    ev.preventDefault();
}

async function drop(ev) {
    ev.preventDefault();
    const data = ev.dataTransfer.getData("text");
    const card = document.getElementById(data);
    if (!card) return;
    
    card.classList.remove('opacity-50');
    
    const targetCol = ev.currentTarget;
    const newStatus = targetCol.dataset.status;
    const storyKey = card.id.replace('card-', '');
    
    // Find the story in our local state
    const story = boardData.stories.find(s => s.key === storyKey);
    if (!story || story.status === newStatus) return;
    
    // Optimistic UI updates
    const oldStatus = story.status;
    story.status = newStatus;
    renderBoard();
    
    try {
        const response = await fetch(`${API_URL}/api/story/status`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ key: storyKey, status: newStatus })
        });
        
        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.error || 'Server status update failed');
        }
        
        // Auto-pin active story when dragged/moved to in-progress
        if (newStatus === 'in-progress') {
            activeStoryKey = storyKey;
        }
        
        // Reload all data to refresh specifications and statuses
        await loadBoardData();
    } catch (error) {
        console.error('Error updating status:', error);
        alert(`Failed to update status for ${storyKey}: ${error.message}. Reverting change.`);
        // Revert on failure
        story.status = oldStatus;
        renderBoard();
    }
}

document.addEventListener('dragend', (e) => {
    if (e.target.id && e.target.id.startsWith('card-')) {
        e.target.classList.remove('opacity-50');
    }
});

function selectStory(storyKey) {
    selectedStoryId = storyKey;
    selectedEpicKey = null; // deselect epic filter but keep board
    
    // Highlight the card
    renderBoard();
    
    // Open the pane
    document.getElementById('task-pane').classList.remove('translate-x-full');
    renderTaskInspector(storyKey);
}

function closeTaskPane() {
    selectedStoryId = null;
    selectedEpicKey = null;
    document.getElementById('task-pane').classList.add('translate-x-full');
    renderBoard();
}

function formatMarkdown(text) {
    if (!text) return '';
    
    // HTML escape
    let escaped = text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
        
    let lines = escaped.split('\n');
    let formattedLines = [];
    let inList = false;
    
    for (let line of lines) {
        let trimmed = line.trim();
        let listMatch = trimmed.match(/^[-*+]\s+(.*)$/);
        if (listMatch) {
            if (!inList) {
                formattedLines.push('<ul class="list-disc pl-5 space-y-1 my-2 text-slate-300">');
                inList = true;
            }
            let itemContent = formatInlineMarkdown(listMatch[1]);
            formattedLines.push(`<li>${itemContent}</li>`);
        } else {
            if (inList) {
                formattedLines.push('</ul>');
                inList = false;
            }
            if (trimmed === '') {
                formattedLines.push('<div class="h-2"></div>');
            } else {
                let content = formatInlineMarkdown(trimmed);
                formattedLines.push(`<p class="my-1.5 text-slate-300 leading-relaxed">${content}</p>`);
            }
        }
    }
    if (inList) {
        formattedLines.push('</ul>');
    }
    return formattedLines.join('\n');
}

function formatInlineMarkdown(text) {
    // Bold
    text = text.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');
    text = text.replace(/\*(.*?)\*/g, '<em>$1</em>');
    // Inline code
    text = text.replace(/`(.*?)`/g, '<code class="px-1.5 py-0.5 rounded bg-slate-900 font-mono text-xs text-rose-400">$1</code>');
    return text;
}

async function togglePinStory(storyKey) {
    const isCurrentlyPinned = activeStoryKey === storyKey;
    const newPinKey = isCurrentlyPinned ? '' : storyKey;
    
    try {
        const response = await fetch(`${API_URL}/api/story/active`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ key: newPinKey })
        });
        if (!response.ok) throw new Error('Pin toggle failed');
        activeStoryKey = newPinKey || null;
        await loadBoardData();
    } catch (error) {
        console.error('Error toggling pin:', error);
        alert('Failed to update active story pin.');
    }
}

async function transitionStoryStatus(storyKey, targetStatus) {
    try {
        const response = await fetch(`${API_URL}/api/story/status`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ key: storyKey, status: targetStatus })
        });
        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.error || 'Transition failed');
        }
        
        // If transitioning to in-progress, also automatically pin the story
        if (targetStatus === 'in-progress') {
            activeStoryKey = storyKey;
        }
        
        await loadBoardData();
    } catch (error) {
        console.error('Error transitioning status:', error);
        alert(`Failed to transition story status: ${error.message}`);
    }
}

function getTransitionButtons(story) {
    const status = story.status;
    let buttons = [];
    
    if (status === 'backlog') {
        buttons.push({
            label: '🚀 Promote to Ready for Dev',
            class: 'bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white font-bold',
            targetStatus: 'ready-for-dev'
        });
    } else if (status === 'ready-for-dev') {
        buttons.push({
            label: '▶ Start Development',
            class: 'bg-gradient-to-r from-orange-500 to-red-600 hover:from-orange-400 hover:to-red-500 text-white font-bold',
            targetStatus: 'in-progress'
        });
        buttons.push({
            label: '⬅ Move back to Backlog',
            class: 'bg-slate-800 border border-white/5 hover:bg-slate-700 text-slate-300',
            targetStatus: 'backlog'
        });
    } else if (status === 'in-progress') {
        buttons.push({
            label: '🔍 Submit for Review',
            class: 'bg-gradient-to-r from-purple-500 to-indigo-600 hover:from-purple-400 hover:to-indigo-500 text-white font-bold',
            targetStatus: 'review'
        });
        buttons.push({
            label: '⚠️ Mark as Blocked',
            class: 'bg-gradient-to-r from-amber-500 to-amber-600 hover:from-amber-400 hover:to-amber-500 text-slate-900 font-bold',
            targetStatus: 'blocked'
        });
        buttons.push({
            label: '⬅ Stop & Revert to Ready',
            class: 'bg-slate-800 border border-white/5 hover:bg-slate-700 text-slate-300',
            targetStatus: 'ready-for-dev'
        });
    } else if (status === 'blocked') {
        buttons.push({
            label: '▶ Resume Development',
            class: 'bg-gradient-to-r from-orange-500 to-red-600 hover:from-orange-400 hover:to-red-500 text-white font-bold',
            targetStatus: 'in-progress'
        });
        buttons.push({
            label: '⬅ Revert to Ready',
            class: 'bg-slate-800 border border-white/5 hover:bg-slate-700 text-slate-300',
            targetStatus: 'ready-for-dev'
        });
    } else if (status === 'review') {
        buttons.push({
            label: '✅ Approve & Mark Done',
            class: 'bg-gradient-to-r from-emerald-500 to-green-600 hover:from-emerald-400 hover:to-green-500 text-white font-bold',
            targetStatus: 'done'
        });
        buttons.push({
            label: '❌ Request Changes',
            class: 'bg-gradient-to-r from-rose-500 to-rose-600 hover:from-rose-400 hover:to-rose-500 text-white font-bold',
            targetStatus: 'in-progress'
        });
    } else if (status === 'done') {
        buttons.push({
            label: '🔄 Reopen Story',
            class: 'bg-slate-800 border border-white/5 hover:bg-slate-700 text-slate-300',
            targetStatus: 'in-progress'
        });
    }
    
    return buttons;
}

async function renderEpicInspector(epicKey) {
    const container = document.getElementById('task-pane-content');
    container.innerHTML = `
        <div class="flex justify-center items-center py-12">
            <i class="fa-solid fa-spinner fa-spin text-2xl text-dracula-purple"></i>
        </div>
    `;
    
    try {
        const response = await fetch(`${API_URL}/api/epic?id=${epicKey}`);
        if (!response.ok) throw new Error('Failed to load epic details');
        const epicDetails = await response.json();
        
        container.innerHTML = '';
        
        const epic = boardData.epics.find(e => e.key === epicKey);
        const storiesTotal = epic ? epic.stories_total : 0;
        const storiesDone = epic ? epic.stories_done : 0;
        const pct = storiesTotal > 0 ? Math.round(storiesDone / storiesTotal * 100) : 0;
        
        // Render folder skin header
        container.innerHTML += `
            <div class="p-4 rounded-xl bg-slate-900 border border-slate-700/50 shadow-inner space-y-3 relative overflow-hidden">
                <div class="absolute top-0 right-0 w-24 h-24 bg-gradient-to-bl from-dracula-purple/5 to-transparent pointer-events-none"></div>
                <div class="flex items-center gap-2 text-slate-400 text-xs font-mono font-bold">
                    <i class="fa-solid fa-folder-open text-dracula-purple text-sm"></i>
                    <span>${epicKey.toUpperCase()}</span>
                </div>
                <h3 class="text-lg font-bold text-white leading-tight">${epicDetails.title}</h3>
                
                <div class="space-y-1 pt-1">
                    <div class="flex justify-between text-[11px] font-mono text-slate-400 font-bold">
                        <span>Stories: ${storiesDone}/${storiesTotal} Completed</span>
                        <span>${pct}%</span>
                    </div>
                    <div class="w-full bg-slate-950 rounded-full h-1.5 border border-white/5">
                        <div class="bg-gradient-to-r from-dracula-purple to-dracula-pink h-1.5 rounded-full transition-all duration-500" style="width: ${pct}%"></div>
                    </div>
                </div>
            </div>
        `;
        
        // Scope & Details
        container.innerHTML += `
            <div class="space-y-2">
                <h4 class="text-xs font-bold uppercase tracking-wider text-slate-400"><i class="fa-solid fa-align-left"></i> Scope & Details</h4>
                <div class="p-4 bg-slate-900/40 rounded-xl border border-white/5 overflow-y-auto max-h-[calc(100vh-320px)] prose-sm">
                    ${formatMarkdown(epicDetails.description)}
                </div>
            </div>
        `;
        
        // Child stories list
        const epicStories = boardData.stories.filter(s => s.epic === epicKey);
        if (epicStories.length > 0) {
            const listDiv = document.createElement('div');
            listDiv.className = 'space-y-2 pt-2';
            listDiv.innerHTML = `<h4 class="text-xs font-bold uppercase tracking-wider text-slate-400"><i class="fa-solid fa-list-ol"></i> Included Stories</h4>`;
            
            epicStories.forEach(s => {
                const statusColors = s.status === 'done' ? 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/20' 
                                     : s.status === 'review' ? 'bg-purple-500/15 text-purple-400 border border-purple-500/20'
                                     : s.status === 'blocked' ? 'bg-amber-500/15 text-amber-400 border border-amber-500/20'
                                     : s.status === 'in-progress' ? 'bg-orange-500/15 text-orange-400 border border-orange-500/20'
                                     : 'bg-slate-800/80 text-slate-400 border border-white/5';
                                     
                listDiv.innerHTML += `
                    <div onclick="selectStory('${s.key}')" class="p-3 bg-slate-900/60 hover:bg-slate-800/60 border border-white/5 rounded-xl flex justify-between items-center cursor-pointer transition">
                        <div class="flex flex-col min-w-0 pr-2">
                            <span class="text-[10px] font-mono text-slate-500 font-bold">${s.key}</span>
                            <span class="text-sm font-semibold text-slate-200 truncate leading-snug">${s.title}</span>
                        </div>
                        <span class="text-[9px] font-mono font-bold px-2 py-0.5 rounded shrink-0 uppercase ${statusColors}">${s.status}</span>
                    </div>
                `;
            });
            container.appendChild(listDiv);
        }
        
    } catch (error) {
        console.error('Error loading epic details:', error);
        container.innerHTML = `
            <div class="text-rose-400 text-center py-8">
                <i class="fa-solid fa-triangle-exclamation text-2xl mb-2 block"></i>
                Error loading epic details.
            </div>
        `;
    }
}

async function renderTaskInspector(storyKey) {
    const container = document.getElementById('task-pane-content');
    container.innerHTML = `
        <div class="flex justify-center items-center py-12">
            <i class="fa-solid fa-spinner fa-spin text-2xl text-dracula-purple"></i>
        </div>
    `;
    
    try {
        const response = await fetch(`${API_URL}/api/story?id=${storyKey}`);
        if (!response.ok) throw new Error('Failed to load story tasks');
        const storyDetails = await response.json();
        
        container.innerHTML = '';
        
        const story = boardData.stories.find(s => s.key === storyKey);
        if (!story) return;
        
        const isPinned = activeStoryKey === storyKey;
        const specBadge = story.file_exists 
            ? `<span class="px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 text-xs font-semibold flex items-center gap-1"><i class="fa-solid fa-file-invoice"></i> Spec File Created</span>` 
            : `<span class="px-2 py-0.5 rounded bg-rose-500/10 text-rose-400 border border-rose-500/20 text-xs font-semibold flex items-center gap-1"><i class="fa-solid fa-file-circle-xmark"></i> Specification Missing</span>`;
            
        const regenButton = story.file_exists
            ? `<button onclick="createStorySpec('${storyKey}', true)" class="px-2 py-0.5 rounded bg-slate-800 text-slate-300 border border-white/10 text-xs font-semibold hover:bg-slate-700 hover:text-white transition flex items-center gap-1" title="Regenerate/Re-scaffold the specification file"><i class="fa-solid fa-rotate text-[10px]"></i> Regenerate Spec</button>`
            : '';

        const pinIconColor = isPinned ? 'text-rose-500' : 'text-slate-500 hover:text-rose-400 opacity-60 hover:opacity-100';
        
        // Compute checklist stats
        const tasksTot = storyDetails.tasks ? storyDetails.tasks.length : 0;
        const tasksDone = storyDetails.tasks ? storyDetails.tasks.filter(t => t.done).length : 0;
        const pct = tasksTot > 0 ? Math.round(tasksDone / tasksTot * 100) : 0;
        
        let headerHtml = `
            <div class="space-y-3 pb-3 border-b border-white/5">
                <div class="flex justify-between items-center">
                    <span class="text-xs font-bold font-mono text-slate-400">${storyKey}</span>
                    <button onclick="togglePinStory('${storyKey}')" class="transition flex items-center gap-1" title="${isPinned ? 'Unpin Active Story' : 'Pin as Active Story'}">
                        <i class="fa-solid fa-thumbtack ${pinIconColor} text-sm"></i>
                        <span class="text-[10px] font-semibold text-slate-500 ${isPinned ? 'text-rose-400' : ''}">${isPinned ? 'Pinned' : 'Pin'}</span>
                    </button>
                </div>
                <h3 class="text-base font-bold text-white leading-tight">${storyDetails.title}</h3>
                
                <div class="flex flex-wrap gap-2 items-center">
                    ${specBadge}
                    ${regenButton}
                    <span class="px-2 py-0.5 rounded bg-slate-800 text-slate-400 text-xs font-mono uppercase font-bold">${story.epic || ''}</span>
                </div>

                <!-- Antigravity CLI Command Box -->
                <div class="mt-2 space-y-1.5 p-2.5 bg-slate-900/60 rounded-xl border border-white/5">
                    <span class="text-[10px] font-bold text-slate-400 uppercase tracking-wider block"><i class="fa-solid fa-terminal text-dracula-purple"></i> Run in Antigravity</span>
                    <div class="p-2 bg-slate-950 rounded-lg border border-white/5 font-mono text-[10px] text-slate-300 relative group flex items-center justify-between">
                        <span class="truncate pr-8 select-all"><span class="text-dracula-purple select-none">$</span> dev this story _bmad-output/implementation-artifacts/${storyKey}.md</span>
                        <button onclick="navigator.clipboard.writeText('dev this story _bmad-output/implementation-artifacts/${storyKey}.md'); showCopyToast(this)" class="absolute top-1.5 right-1.5 opacity-60 group-hover:opacity-100 hover:text-white transition text-slate-400" title="Copy command">
                            <i class="fa-regular fa-copy"></i>
                        </button>
                    </div>
                    ${isPinned ? `
                    <div class="p-2 bg-rose-950/20 rounded-lg border border-rose-500/10 font-mono text-[10px] text-rose-300 relative group flex items-center justify-between">
                        <span class="truncate pr-8 select-all"><span class="text-rose-400 select-none">$</span> dev this story</span>
                        <button onclick="navigator.clipboard.writeText('dev this story'); showCopyToast(this)" class="absolute top-1.5 right-1.5 opacity-60 group-hover:opacity-100 hover:text-rose-200 transition text-rose-400" title="Copy shorthand command">
                            <i class="fa-regular fa-copy"></i>
                        </button>
                    </div>
                    <span class="text-[9px] text-rose-400/80 leading-normal block">💡 This story is pinned as active. You can run the shorthand command!</span>
                    ` : ''}
                </div>
                
                <!-- Persistent Progress Header -->
                <div class="space-y-1 pt-1">
                    <div class="flex justify-between text-[11px] font-mono text-slate-400 font-bold">
                        <span>Checklist: ${tasksDone}/${tasksTot} Tasks</span>
                        <span>${pct}%</span>
                    </div>
                    <div class="w-full bg-slate-900 rounded-full h-1 border border-white/5">
                        <div class="bg-gradient-to-r from-dracula-purple to-dracula-pink h-1 rounded-full transition-all duration-500" style="width: ${pct}%"></div>
                    </div>
                </div>
                
                <!-- Tab Switching Panel -->
                <div class="flex gap-2 border-b border-white/5 pt-1">
                    <button onclick="switchInspectorTab('checklist')" class="flex-1 py-1.5 text-xs font-bold transition-all border-b-2 ${activeInspectorTab === 'checklist' ? 'border-dracula-purple text-white' : 'border-transparent text-slate-400 hover:text-slate-200'}">
                        Checklist
                     </button>
                    <button onclick="switchInspectorTab('details')" class="flex-1 py-1.5 text-xs font-bold transition-all border-b-2 ${activeInspectorTab === 'details' ? 'border-dracula-purple text-white' : 'border-transparent text-slate-400 hover:text-slate-200'}">
                        Details & ACs
                    </button>
                </div>
            </div>
        `;
        
        container.innerHTML = headerHtml;
        
        // Render Tab Body
        const tabBody = document.createElement('div');
        tabBody.className = 'flex-1 py-2';
        
        if (activeInspectorTab === 'checklist') {
            if (storyDetails.tasks && storyDetails.tasks.length > 0) {
                const listDiv = document.createElement('div');
                listDiv.className = 'space-y-2';
                storyDetails.tasks.forEach(task => {
                    const checked = task.done ? 'checked' : '';
                    const lineThrough = task.done ? 'line-through text-slate-500' : 'text-slate-200';
                    listDiv.innerHTML += `
                        <label class="flex items-start gap-3 p-3 bg-slate-900/60 hover:bg-slate-800/50 border border-white/5 rounded-xl cursor-pointer transition select-none">
                            <input type="checkbox" ${checked} onchange="toggleTaskCheckbox('${storyKey}', ${task.line_no}, this.checked)" class="w-4 h-4 rounded border-slate-700 bg-slate-950 text-dracula-purple focus:ring-dracula-purple/50 focus:ring-offset-slate-950 mt-0.5">
                            <span class="text-sm font-medium leading-normal ${lineThrough}">${task.text}</span>
                        </label>
                    `;
                });
                tabBody.appendChild(listDiv);
            } else {
                tabBody.innerHTML = `
                    <div class="text-slate-500 text-center py-8">
                        <i class="fa-solid fa-clipboard-question text-xl mb-1.5 block"></i>
                        No tasks parsed from spec file.
                    </div>
                `;
            }
            
            // Add scaffold button if spec is missing
            if (!story.file_exists) {
                tabBody.innerHTML += `
                    <div class="p-3 bg-slate-900 border border-white/5 rounded-xl space-y-3 mt-3">
                        <p class="text-xs text-slate-400 leading-relaxed">This story has no Markdown specification file yet.</p>
                        <button onclick="createStorySpec('${storyKey}')" class="w-full py-2 bg-gradient-to-r from-dracula-purple to-dracula-pink text-slate-900 font-bold rounded-lg text-xs hover:opacity-90 shadow-md transition flex items-center justify-center gap-1.5">
                            <i class="fa-solid fa-file-medical"></i> Scaffold Spec File
                        </button>
                    </div>
                `;
            }
        } else {
            // Details & ACs Tab
            const detailsDiv = document.createElement('div');
            detailsDiv.className = 'space-y-4 overflow-y-auto max-h-[calc(100vh-325px)] pr-1';
            
            if (storyDetails.story_text) {
                detailsDiv.innerHTML += `
                    <div class="space-y-1">
                        <h5 class="text-[11px] font-bold text-slate-400 uppercase tracking-wider">Story Definition</h5>
                        <div class="p-3 bg-slate-900/40 rounded-xl border border-white/5 text-sm prose prose-sm text-slate-300">
                            ${formatMarkdown(storyDetails.story_text)}
                        </div>
                    </div>
                `;
            }
            if (storyDetails.ac_text) {
                detailsDiv.innerHTML += `
                    <div class="space-y-1">
                        <h5 class="text-[11px] font-bold text-slate-400 uppercase tracking-wider">Acceptance Criteria</h5>
                        <div class="p-3 bg-slate-900/40 rounded-xl border border-white/5 text-sm prose prose-sm text-slate-300">
                            ${formatMarkdown(storyDetails.ac_text)}
                        </div>
                    </div>
                `;
            }
            if (storyDetails.notes_text) {
                detailsDiv.innerHTML += `
                    <div class="space-y-1">
                        <h5 class="text-[11px] font-bold text-slate-400 uppercase tracking-wider">Developer Notes</h5>
                        <div class="p-3 bg-slate-900/40 rounded-xl border border-white/5 text-sm prose prose-sm text-slate-300">
                            ${formatMarkdown(storyDetails.notes_text)}
                        </div>
                    </div>
                `;
            }
            
            if (!storyDetails.story_text && !storyDetails.ac_text && !storyDetails.notes_text) {
                detailsDiv.innerHTML = `
                    <div class="text-slate-500 text-center py-8">
                        <i class="fa-solid fa-circle-info text-xl mb-1.5 block"></i>
                        No story details available.
                    </div>
                `;
            }
            tabBody.appendChild(detailsDiv);
        }
        
        container.appendChild(tabBody);
        
        // Render Swimlane Action Buttons
        const buttons = getTransitionButtons(story);
        if (buttons.length > 0) {
            const btnContainer = document.createElement('div');
            btnContainer.className = 'pt-4 border-t border-white/5 space-y-2';
            buttons.forEach(btn => {
                btnContainer.innerHTML += `
                    <button onclick="transitionStoryStatus('${storyKey}', '${btn.targetStatus}')" class="w-full py-2.5 rounded-xl text-xs flex items-center justify-center gap-1.5 transition ${btn.class}">
                        ${btn.label}
                    </button>
                `;
            });
            container.appendChild(btnContainer);
        }
        
    } catch (error) {
        console.error('Error loading task inspector details:', error);
        container.innerHTML = `
            <div class="text-rose-400 text-center py-8">
                <i class="fa-solid fa-triangle-exclamation text-2xl mb-2 block"></i>
                Error loading story details.
            </div>
        `;
    }
}

function switchInspectorTab(tab) {
    activeInspectorTab = tab;
    if (selectedStoryId) {
        renderTaskInspector(selectedStoryId);
    }
}

async function createStorySpec(storyKey, overwrite = false) {
    if (overwrite && !confirm(`Are you sure you want to regenerate the specification file for ${storyKey}? This will overwrite the existing file and reset any checked tasks.`)) {
        return;
    }
    try {
        const response = await fetch(`${API_URL}/api/story/create`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ key: storyKey, overwrite: overwrite })
        });
        
        if (!response.ok) throw new Error('Scaffolding spec failed');
        
        await loadBoardData();
        renderTaskInspector(storyKey);
    } catch (error) {
        console.error('Error scaffolding story:', error);
        alert(`Failed to scaffold story specification file for ${storyKey}`);
    }
}

async function toggleTaskCheckbox(storyKey, lineNo, isChecked) {
    try {
        const response = await fetch(`${API_URL}/api/story/tasks`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                key: storyKey,
                tasks: [{ line_no: lineNo, done: isChecked }]
            })
        });
        
        if (!response.ok) throw new Error('Task checkbox toggle failed');
        
        await loadBoardData();
    } catch (error) {
        console.error('Error toggling task checkbox:', error);
        alert(`Failed to save task update to story file on disk.`);
        await loadBoardData();
    }
}

function showCopyToast(button) {
    const icon = button.querySelector('i');
    icon.className = 'fa-solid fa-check text-emerald-400';
    setTimeout(() => {
        icon.className = 'fa-regular fa-copy';
    }, 1500);
}

// Initial Board Load on start
document.addEventListener('DOMContentLoaded', () => {
    loadBoardData();
    
    // Auto reload board data every 30 seconds
    setInterval(loadBoardData, 30000);
});
