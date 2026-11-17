import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// A sticker's picture, shared as a PNG file.
struct StickerFile: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .png) { SentTransferredFile($0.url) }
    }
}

private struct StickerGrid: View {
    let tapped: (Sticker) -> Void
    @EnvironmentObject private var stickers: StickerLibrary
    private let columns = [GridItem(.adaptive(minimum: 84), spacing: 10)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 10) {
                ForEach(stickers.items) { sticker in
                    Button { tapped(sticker) } label: {
                        if let image = stickers.image(sticker) {
                            Image(uiImage: image).resizable().scaledToFit().frame(height: 84)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Sticker")
                }
            }
            .padding()
        }
    }
}

/// Choosing one of one's stickers to send.
struct StickerPicker: View {
    let chosen: (Sticker) -> Void
    @EnvironmentObject private var stickers: StickerLibrary
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if stickers.items.isEmpty {
                    ContentUnavailableView(
                        "No stickers yet", systemImage: "face.smiling",
                        description: Text("Make one in the Stickers tab, or hold a picture someone sent and choose Add to my stickers.")
                    )
                } else {
                    StickerGrid(tapped: chosen)
                }
            }
            .navigationTitle("Send a sticker")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}

/// The Stickers tab: one's stickers, and making, editing, sharing and deleting them.
struct StickersView: View {
    @EnvironmentObject private var stickers: StickerLibrary
    @State private var making = false
    @State private var selected: Sticker?
    @State private var editing: Sticker?

    var body: some View {
        Group {
            if stickers.items.isEmpty {
                ContentUnavailableView {
                    Label("No stickers yet", systemImage: "face.smiling")
                } description: {
                    Text("Turn a photo into a sticker: the subject is cut out and outlined in white.")
                } actions: {
                    Button("Make a sticker") { making = true }.buttonStyle(.borderedProminent)
                }
            } else {
                StickerGrid { selected = $0 }
            }
        }
        .navigationTitle("Stickers")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { making = true } label: { Image(systemName: "plus") }.accessibilityLabel("Make a sticker")
            }
        }
        .sheet(isPresented: $making) { StickerMakerView(editing: nil) }
        .sheet(item: $editing) { StickerMakerView(editing: $0) }
        .sheet(item: $selected) { sticker in
            StickerDetail(sticker: sticker) { editing = sticker }
        }
    }
}

/// One sticker, with what can be done with it: share it into any app, edit it, delete it.
private struct StickerDetail: View {
    let sticker: Sticker
    let edit: () -> Void
    @EnvironmentObject private var stickers: StickerLibrary
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                if let image = stickers.image(sticker) {
                    Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 220)
                }
                ShareLink(item: StickerFile(url: sticker.url), preview: SharePreview("Sticker", image: Image(systemName: "face.smiling"))) {
                    Label("Share (Messages, WhatsApp…)", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                Button {
                    dismiss()
                    edit()
                } label: {
                    Label("Edit", systemImage: "slider.horizontal.3").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                Button(role: .destructive) {
                    stickers.delete(sticker)
                    dismiss()
                } label: {
                    Label("Delete", systemImage: "trash").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                Spacer()
            }
            .padding()
            .navigationTitle("Sticker")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}

/// Making a sticker from a photo, or editing one made before (its original picture and choices are kept).
struct StickerMakerView: View {
    let editing: Sticker?
    @EnvironmentObject private var stickers: StickerLibrary
    @Environment(\.dismiss) private var dismiss
    @State private var photo: PhotosPickerItem?
    @State private var original: UIImage?
    @State private var options = StickerOptions()
    @State private var preview: UIImage?
    @State private var subjects: SubjectFinder?
    @State private var finding = false
    @State private var nothingFound = false
    @State private var choosing = false
    @State private var choosingPicture: UIImage?
    @State private var pictureSize: CGSize = .zero

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if choosing, let choosingPicture {
                        Image(uiImage: choosingPicture).resizable().scaledToFit()
                            .background(GeometryReader { geo in Color.clear.onAppear { pictureSize = geo.size }.onChange(of: geo.size) { _, s in pictureSize = s } })
                            .onTapGesture { point in tapped(point) }
                        Text("Tap each person or thing to keep or drop it. What is dimmed is left out.").font(.footnote).foregroundStyle(.secondary)
                        Button("Done") { choosing = false }
                    } else if let preview {
                        Image(uiImage: preview).resizable().scaledToFit().frame(maxHeight: 260).frame(maxWidth: .infinity)
                            .background(Checkerboard())
                    } else {
                        Text("Choose a photo to turn into a sticker.").foregroundStyle(.secondary)
                    }
                    if editing == nil {
                        PhotosPicker(selection: $photo, matching: .images) { Label(original == nil ? "Choose a photo" : "Choose another photo", systemImage: "photo") }
                    }
                }
                if original != nil {
                    Section("Cut-out") {
                        Toggle("Smart cut (finds the subject)", isOn: $options.smartCut)
                        if finding { ProgressView("Finding the subject…") }
                        if options.smartCut, nothingFound {
                            Text("Nothing stood out in this picture (or the finder can't run here), so the whole picture is used.")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                        if options.smartCut, let subjects, subjects.instances.count > 1 {
                            Button("Choose who or what to keep") { startChoosing() }
                        }
                    }
                    Section("Look") {
                        Toggle("White outline", isOn: $options.outline)
                        if options.outline {
                            VStack(alignment: .leading) {
                                Text("Outline thickness").font(.footnote).foregroundStyle(.secondary)
                                Slider(value: $options.outlineWidth, in: 2...40, step: 1)
                            }
                        }
                        VStack(alignment: .leading) {
                            Text("Sharpen").font(.footnote).foregroundStyle(.secondary)
                            Slider(value: $options.sharpen, in: 0...100, step: 5)
                        }
                        TextField("Title under the sticker (optional)", text: $options.caption)
                        Toggle("White background", isOn: $options.whiteBackground)
                    }
                    if editing != nil {
                        Section {
                            Button("Back to the original picture") {
                                options = StickerOptions()
                                options.outline = false
                            }
                        }
                    }
                }
            }
            .navigationTitle(editing == nil ? "New sticker" : "Edit sticker")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if editing != nil {
                        Menu("Save") {
                            Button("Save changes") { save(asNew: false) }
                            Button("Save as new") { save(asNew: true) }
                        }
                        .disabled(preview == nil)
                    } else {
                        Button("Save") { save(asNew: true) }.disabled(preview == nil)
                    }
                }
            }
            .task { loadForEditing() }
            .onChange(of: photo) { _, item in
                guard let item else { return }
                Task { await load(item) }
            }
            .task(id: RenderKey(options: options, picture: original.map { ObjectIdentifier($0) }, found: subjects != nil)) { await render() }
            .onChange(of: options.smartCut) { _, on in if on { findSubjects() } }
        }
    }

    private struct RenderKey: Equatable {
        let options: StickerOptions
        let picture: ObjectIdentifier?
        let found: Bool
    }

    private func loadForEditing() {
        guard let editing, original == nil else { return }
        if let project = stickers.project(for: editing) {
            original = project.original
            options = project.options
        } else if let image = stickers.image(editing) {
            original = image
            options.outline = false
        }
        if options.smartCut { findSubjects() }
    }

    private func load(_ item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) else { return }
        original = StickerEngine.fitted(image, maxSide: 1024)
        subjects = nil
        nothingFound = false
        choosing = false
        options.keep = []
        if options.smartCut { findSubjects() }
    }

    /// Looks for the picture's subjects, once per picture, off the main thread.
    private func findSubjects() {
        guard subjects == nil, !finding, let picture = original, let cg = picture.cgImage else { return }
        finding = true
        nothingFound = false
        Task {
            let found = await Task.detached(priority: .userInitiated) { SubjectFinder.find(in: cg) }.value
            guard picture === original else { finding = false; return }
            subjects = found
            nothingFound = found == nil
            finding = false
        }
    }

    private func render() async {
        guard let picture = original else { preview = nil; return }
        let current = options
        let found = subjects
        // A moment's pause, so dragging a slider renders the last position, not every one.
        try? await Task.sleep(nanoseconds: 60_000_000)
        if Task.isCancelled { return }
        let made = await Task.detached(priority: .userInitiated) { StickerEngine.make(picture, options: current, subjects: found) }.value
        if Task.isCancelled { return }
        preview = made
        if choosing { refreshChoosing() }
    }

    private func startChoosing() {
        choosing = true
        refreshChoosing()
    }

    private func refreshChoosing() {
        guard let picture = original, let subjects else { return }
        choosingPicture = StickerEngine.choosingPreview(picture, subjects: subjects, keep: options.keep)
    }

    /// A tap on the picture: the person or thing there is kept, or dropped if it was kept.
    private func tapped(_ point: CGPoint) {
        guard let subjects, pictureSize.width > 0, pictureSize.height > 0,
              let instance = subjects.instance(atX: point.x / pictureSize.width, y: point.y / pictureSize.height) else { return }
        var keep = options.keep.isEmpty ? subjects.instances : options.keep
        if let index = keep.firstIndex(of: instance) {
            if keep.count > 1 { keep.remove(at: index) } // something has to stay
        } else {
            keep.append(instance)
        }
        options.keep = keep.count == subjects.instances.count ? [] : keep.sorted()
        refreshChoosing()
    }

    private func save(asNew: Bool) {
        guard let original, let preview else { return }
        stickers.save(preview, original: original, options: options, replacing: asNew ? nil : editing)
        dismiss()
    }
}

/// The grey squares behind a sticker's preview, so what is see-through shows.
private struct Checkerboard: View {
    var body: some View {
        Canvas { context, size in
            let cell: CGFloat = 10
            for row in 0..<Int(size.height / cell + 1) {
                for column in 0..<Int(size.width / cell + 1) where (row + column) % 2 == 0 {
                    context.fill(Path(CGRect(x: CGFloat(column) * cell, y: CGFloat(row) * cell, width: cell, height: cell)), with: .color(.gray.opacity(0.18)))
                }
            }
        }
    }
}
