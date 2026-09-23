import SwiftUI

/// iPhone gets a tab bar, iPad gets a sidebar. Same screens either way.
public struct RootView: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(AppModel.self) private var app
    @Bindable var chat: ChatModel
    let models: ModelsModel
    let queue: QueueModel

    public init(chat: ChatModel, models: ModelsModel, queue: QueueModel) {
        self.chat = chat
        self.models = models
        self.queue = queue
    }

    public var body: some View {
        Group {
            if sizeClass == .compact {
                PhoneTabs(chat: chat)
            } else {
                PadSplit(chat: chat)
            }
        }
        // One `GET /events` for the whole app, opened when there is a Mac to open it
        // against and reopened when that Mac changes.
        .task { app.events.start(using: app.transport) }
        .onChange(of: app.connectionGeneration) { _, _ in
            app.events.start(using: app.transport)
            chat.macChanged()
            Task { await chat.loadConversations(using: app.transport) }
            // Whatever the list was following belongs to the last Mac: stopped, and read
            // afresh from this one.
            models.reset()
            Task { await models.refresh(using: app.transport) }
            // The last Mac's queue, and anything still on its way to it, is not this one's.
            queue.connect(app.connectionGeneration)
        }
        // What the Mac pushes is what the list shows, and a load it started is followed by
        // it — `POST /load` may answer "still loading" and carry on — on whichever screen.
        .onChange(of: app.events.status) { _, pushed in
            if let pushed { models.statusChanged(pushed) }
        }
        .onChange(of: app.events.isLive, initial: true) { _, live in models.eventsLive = live }
        .environment(models)
        .environment(queue)
        // Answer checks arrive after the reply they are about, on the shared event
        // stream, so they are applied wherever the chat screen happens to be.
        .onChange(of: app.events.verdicts) { _, verdicts in
            guard let id = chat.current?.id else { return }
            if let verdict = verdicts[id] ?? verdicts[""] { chat.apply(verdict: verdict) }
        }
    }
}

struct PhoneTabs: View {
    @Environment(AppModel.self) private var app
    @Bindable var chat: ChatModel
    @State private var openConversation: String?
    @State private var tab = Tab.dashboard

    enum Tab: Hashable { case dashboard, models, queue, chat, settings }

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack {
                DashboardView()
            }
            .tabItem { Label("Dashboard", systemImage: "gauge.with.dots.needle.33percent") }
            .tag(Tab.dashboard)

            NavigationStack {
                ModelsView()
            }
            .tabItem { Label("Models", systemImage: "square.stack.3d.up") }
            .tag(Tab.models)

            NavigationStack {
                QueueView()
            }
            .tabItem { Label("Queue", systemImage: "film.stack") }
            .tag(Tab.queue)

            NavigationStack {
                ConversationListView(model: chat) { id in openConversation = id }
                    .navigationDestination(item: $openConversation) { id in
                        ChatView(model: chat)
                            .task(id: id) { await chat.open(id: id, using: app.transport) }
                    }
            }
            .tabItem { Label("Chat", systemImage: "bubble.left.and.bubble.right") }
            .tag(Tab.chat)

            NavigationStack {
                SettingsView(chat: chat)
            }
            .tabItem { Label("Settings", systemImage: "gearshape") }
            .tag(Tab.settings)
        }
        // A widget's "Ask" button, or a `siliconbuddy://ask` link: the Chat tab, on the
        // newest conversation, with the text typed in and nothing sent.
        .onChange(of: app.pendingCompose) { _, request in
            guard let request else { return }
            tab = .chat
            Task {
                if chat.current == nil {
                    await chat.newConversation(using: app.transport)
                }
                if let id = chat.current?.id { openConversation = id }
                if let text = request.text { chat.draft = text }
                app.pendingCompose = nil
            }
        }
    }
}

struct PadSplit: View {
    @Environment(AppModel.self) private var app
    @Bindable var chat: ChatModel
    @State private var selection: SidebarItem? = .dashboard
    @State private var columns = NavigationSplitViewVisibility.all

    enum SidebarItem: Hashable {
        case dashboard, models, queue, settings
        case conversation(String)
    }

    var body: some View {
        NavigationSplitView(columnVisibility: $columns) {
            List(selection: $selection) {
                Section {
                    Label("Dashboard", systemImage: "gauge.with.dots.needle.33percent")
                        .tag(SidebarItem.dashboard)
                    Label("Models", systemImage: "square.stack.3d.up")
                        .tag(SidebarItem.models)
                    Label("Render queue", systemImage: "film.stack")
                        .tag(SidebarItem.queue)
                    Label("Settings", systemImage: "gearshape")
                        .tag(SidebarItem.settings)
                }
                Section {
                    ForEach(chat.conversations) { summary in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(summary.title).lineLimit(1)
                            Text("\(summary.messageCount) messages")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .tag(SidebarItem.conversation(summary.id))
                    }
                } header: {
                    HStack {
                        Text("Conversations")
                        Spacer()
                        Button {
                            Task {
                                await chat.newConversation(using: app.transport)
                                if let id = chat.current?.id {
                                    selection = .conversation(id)
                                }
                            }
                        } label: {
                            Image(systemName: "square.and.pencil")
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("New conversation")
                    }
                } footer: {
                    Text(chat.storageNote)
                }
            }
            .listStyle(.sidebar)
            .navigationTitle("Silicon Buddy")
            .task { await chat.loadConversations(using: app.transport) }
            .onChange(of: app.pendingCompose) { _, request in
                guard let request else { return }
                Task {
                    if chat.current == nil {
                        await chat.newConversation(using: app.transport)
                    }
                    if let id = chat.current?.id { selection = .conversation(id) }
                    if let text = request.text { chat.draft = text }
                    app.pendingCompose = nil
                }
            }
        } detail: {
            NavigationStack {
                switch selection {
                case .dashboard, .none:
                    DashboardView()
                case .models:
                    ModelsView()
                case .queue:
                    QueueView()
                case .settings:
                    SettingsView(chat: chat)
                case .conversation(let id):
                    ChatView(model: chat)
                        .task(id: id) { await chat.open(id: id, using: app.transport) }
                }
            }
        }
    }
}
