import SwiftUI

/// iPhone gets a tab bar, iPad gets a sidebar. Same screens either way.
public struct RootView: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(AppModel.self) private var app
    @Bindable var chat: ChatModel

    public init(chat: ChatModel) { self.chat = chat }

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
        }
    }
}

struct PhoneTabs: View {
    @Environment(AppModel.self) private var app
    @Bindable var chat: ChatModel
    @State private var openConversation: String?

    var body: some View {
        TabView {
            NavigationStack {
                DashboardView()
            }
            .tabItem { Label("Dashboard", systemImage: "gauge.with.dots.needle.33percent") }

            NavigationStack {
                ModelsView()
            }
            .tabItem { Label("Models", systemImage: "square.stack.3d.up") }

            NavigationStack {
                ConversationListView(model: chat) { id in openConversation = id }
                    .navigationDestination(item: $openConversation) { id in
                        ChatView(model: chat)
                            .task(id: id) { await chat.open(id: id, using: app.transport) }
                    }
            }
            .tabItem { Label("Chat", systemImage: "bubble.left.and.bubble.right") }

            NavigationStack {
                SettingsView(chat: chat)
            }
            .tabItem { Label("Settings", systemImage: "gearshape") }
        }
    }
}

struct PadSplit: View {
    @Environment(AppModel.self) private var app
    @Bindable var chat: ChatModel
    @State private var selection: SidebarItem? = .dashboard
    @State private var columns = NavigationSplitViewVisibility.all

    enum SidebarItem: Hashable {
        case dashboard, models, settings
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
        } detail: {
            NavigationStack {
                switch selection {
                case .dashboard, .none:
                    DashboardView()
                case .models:
                    ModelsView()
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
