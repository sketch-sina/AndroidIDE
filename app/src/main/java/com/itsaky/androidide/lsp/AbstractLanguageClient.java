package com.itsaky.androidide.lsp;

import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import com.blankj.utilcode.util.FileIOUtils;
import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.ThrowableUtils;
import com.google.gson.Gson;
import com.itsaky.androidide.EditorActivity;
import com.itsaky.androidide.R;
import com.itsaky.androidide.adapters.DiagnosticsAdapter;
import com.itsaky.androidide.adapters.SearchListAdapter;
import com.itsaky.androidide.fragments.EditorFragment;
import com.itsaky.androidide.interfaces.EditorActivityProvider;
import com.itsaky.androidide.models.DiagnosticGroup;
import com.itsaky.androidide.models.SearchResult;
import com.itsaky.androidide.utils.LSPUtils;
import com.itsaky.androidide.utils.Logger;
import com.itsaky.lsp.services.IDELanguageClient;
import com.itsaky.lsp.services.IDELanguageServer;
import io.github.rosemoe.editor.text.Content;
import io.github.rosemoe.editor.widget.CodeEditor;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ShowDocumentParams;
import org.eclipse.lsp4j.ShowDocumentResult;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureInformation;
import com.itsaky.lsp.SemanticHighlight;

/**
 * AndroidIDE specific implementation of the LanguageClient
 */
public abstract class AbstractLanguageClient implements IDELanguageClient {

    protected static final Gson gson = new Gson();
    protected static final Logger LOG = Logger.instance("AbstractLanguageClient");

    protected EditorActivityProvider activityProvider;
    
    private final Map<File, List<Diagnostic>> diagnostics = new HashMap<>();
    private final StarterListener starterListener;
    private final OnConnectedListener onConnectedListener;

    private boolean isConnected;

    public AbstractLanguageClient(StarterListener starterListener, OnConnectedListener onConnectedListener) {
        this.starterListener = starterListener;
        this.onConnectedListener = onConnectedListener;
    }

    public void setActivityProvider(EditorActivityProvider provider) {
        this.activityProvider = provider;
    }

    /**
     * Are we connected to the server?
     */
    public boolean isConnected() {
        return isConnected;
    }

    /**
     * Called when the LanguageServer is connected successfully
     */
    protected void onServerConnected(IDELanguageServer server) {
        this.isConnected = true;
        if (onConnectedListener != null)
            onConnectedListener.onConnected(server);
    }

    /**
     * Called when the connection to the LanguageServer was cancelled.
     */
    protected void onServerDisconnected() {
        this.isConnected = false;
    }

    /**
     * Called when the ServerSocket is started. At this point, the {@link LSPClient} waits for the server to connect. <br>
     * This is (probably) the proper time to start your language server as ServerSocket is waiting for a client to connect.
     */
    protected void startServer() {
        if (this.starterListener != null) {
            starterListener.startServer();
        }
    }

    protected EditorActivity activity() {
        if (activityProvider == null) return null;
        return activityProvider.provide();
    }

    @Override
    public void semanticHighlights(SemanticHighlight highlights) {
        final File file = new File(URI.create(highlights.uri));
        final EditorFragment editor = activity().getPagerAdapter().findEditorByFile(file);
        
        if(editor != null) {
            editor.getEditor().setSemanticHighlights(highlights);
        }
    }
    
    /**
     * Called by {@link io.github.rosemoe.editor.widget.CodeEditor CodeEditor} to show signature help in EditorActivity
     */
    public void showSignatureHelp(SignatureHelp signature, File file) {
        if(signature == null || signature.getSignatures() == null) {
            activity().getBinding().symbolText.setVisibility(View.GONE);
            return;
        }
        SignatureInformation info = signatureWithMostParams(signature);
        if(info == null) return;
        activity().getBinding().symbolText.setText(formatSignature(info, signature.getActiveParameter()));
        final EditorFragment frag = activity().getPagerAdapter().findEditorByFile(file);
        if(frag != null) {
            final CodeEditor editor = frag.getEditor();
            final float[] cursor = editor.getCursorPosition();

            float x = editor.updateCursorAnchor() - (activity().getBinding().symbolText.getWidth() / 2);
            float y = activity().getBinding().editorAppBarLayout.getHeight() + (cursor[0] - editor.getRowHeight() - editor.getOffsetY() - activity().getBinding().symbolText.getHeight());
            activity().getBinding().symbolText.setVisibility(View.VISIBLE);
            activity().positionViewWithinScreen(activity().getBinding().symbolText, x, y);
        }
    }
    
    /**
     * Called by {@link io.github.rosemoe.editor.widget.CodeEditor CodeEditor} to hide signature help in EditorActivity
     */
    public void hideSignatureHelp() {
        activity().getBinding().symbolText.setVisibility(View.GONE);
    }
     
    /**
     * Find the signature with most parameters
     *
     * @param signature The SignatureHelp provided by @{link IDELanguageServer}
     */
    private SignatureInformation signatureWithMostParams(SignatureHelp signature) {
        SignatureInformation signatureWithMostParams = null;
        int mostParamCount = 0;
        final List<SignatureInformation> signatures = signature.getSignatures();
        for(int i=0;i<signatures.size();i++) {
            final SignatureInformation info = signatures.get(i);
            int count = info.getParameters().size();
            if(mostParamCount < count) {
                mostParamCount = count;
                signatureWithMostParams = info;
            }
        }
        return signatureWithMostParams;
    }

    /**
     * Formats (highlights) a method signature
     *
     * @param signature Signature information
     * @param paramIndex Currently active parameter index
     */
    private CharSequence formatSignature(SignatureInformation signature, int paramIndex) {
        String name = signature.getLabel();
        name = name.substring(0, name.indexOf("("));

        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(name, new ForegroundColorSpan(0xffffffff), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("(", new ForegroundColorSpan(0xff4fc3f7), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);

        List<ParameterInformation> params = signature.getParameters();
        for(int i=0;i<params.size();i++) {
            int color = i == paramIndex ? 0xffff6060 : 0xffffffff;
            final ParameterInformation info = params.get(i);
            if(i == params.size() - 1) {
                sb.append(info.getLabel().getLeft() + "", new ForegroundColorSpan(color), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.append(info.getLabel().getLeft() + "", new ForegroundColorSpan(color), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(",", new ForegroundColorSpan(0xff4fc3f7), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(" ");
            }
        }
        sb.append(")", new ForegroundColorSpan(0xff4fc3f7), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams params) {
        boolean error = params == null || params.getDiagnostics() == null || params.getDiagnostics().isEmpty();
        activity().handleDiagnosticsResultVisibility(error);
        
        if(error) return;

        File file = new File(URI.create(params.getUri()));
        if(!file.exists() || !file.isFile()) return;

        diagnostics.put(file, params.getDiagnostics());
        activity().getDiagnosticsList().setAdapter(newDiagnosticsAdapter());

        EditorFragment editor = null;
        if(activity().getPagerAdapter() != null && (editor = activity().getPagerAdapter().findEditorByFile(new File(URI.create(params.getUri())))) != null) {
            editor.setDiagnostics(params.getDiagnostics());
        }
    }
    
    /**
     * Called by {@link io.github.rosemoe.editor.widget.CodeEditor CodeEditor} to show locations in EditorActivity
     */
    public void showLocations(List<? extends Location> locations) {
        
        // Cannot show anything if the activity() is null
        if(activity() == null) {
            return;
        }
        
        boolean error = locations == null || locations.isEmpty();
        activity().handleSearchResultVisibility(error);


        if(error) {
            activity().getSearchResultList().setAdapter(new SearchListAdapter(null, null, null));
            return;
        }

        final Map<File, List<SearchResult>> results = new HashMap<>();
        for(int i=0;i<locations.size();i++) {
            try {
                final Location loc = locations.get(i);
                if(loc == null || loc.getUri() == null || loc.getRange() == null) continue;
                final File file = new File(URI.create(loc.getUri()));
                if(!file.exists() || !file.isFile()) continue;
                EditorFragment frag = activity().getPagerAdapter().findEditorByFile(file);
                Content content;
                if(frag != null && frag.getEditor() != null)
                    content = frag.getEditor().getText();
                else content = new Content(null, FileIOUtils.readFile2String(file));
                final List<SearchResult> matches = results.containsKey(file) ? results.get(file) : new ArrayList<>();
                matches.add(
                    new SearchResult(
                        loc.getRange(),
                        file,
                        content.getLineString(loc.getRange().getStart().getLine()),
                        content.subContent(
                            loc.getRange().getStart().getLine(),
                            loc.getRange().getStart().getCharacter(),
                            loc.getRange().getEnd().getLine(),
                            loc.getRange().getEnd().getCharacter()
                        ).toString()
                    )
                );
                results.put(file, matches);
            } catch (Throwable th) {
                LOG.error(ThrowableUtils.getFullStackTrace(th));
            }
        }

        activity().handleSearchResults(results);
    }

    /**
     * Called by {@link io.github.rosemoe.editor.widget.CodeEditor CodeEditor} to show location links in EditorActivity.
     * These location links are mapped as {@link org.eclipse.lsp4j.Location Location} and then {@link #showLocations(List) } is called.
     */
    public void showLocationLinks(List<? extends LocationLink> locations) {
        
        if(locations == null || locations.size() <= 0) {
            return;
        }
        
        showLocations(locations
            .stream()
                .filter(l -> l != null)
                .map(l -> asLocation(l))
                .filter(l -> l != null)
                .collect(Collectors.toList())
            );
    }

    /**
     * Usually called by {@link io.github.rosemoe.editor.widget.CodeEditor CodeEditor} to show a specific document in EditorActivity and select the specified range
     */
    @Override
    public CompletableFuture<ShowDocumentResult> showDocument(ShowDocumentParams params) {
        ShowDocumentResult result = new ShowDocumentResult();
        boolean success = false;
        
        if(activity() == null) {
            result.setSuccess(success);
            return CompletableFuture.completedFuture(result);
        }
        
        if(params != null && params.getUri() != null && params.getSelection() != null) {
            File file = new File(URI.create(params.getUri()));
            if(file.exists() && file.isFile() && FileUtils.isUtf8(file)) {
                final Range range = params.getSelection();
                EditorFragment frag = activity().getPagerAdapter().getFrag(activity().getBinding().tabs.getSelectedTabPosition());
                if(frag != null
                   && frag.getFile() != null
                   && frag.getEditor() != null
                   && frag.getFile().getAbsolutePath().equals(file.getAbsolutePath())) {
                    if(LSPUtils.isEqual(range.getStart(), range.getEnd())) {
                        frag.getEditor().setSelection(range.getStart().getLine(), range.getStart().getCharacter());
                    } else {
                        frag.getEditor().setSelectionRegion(range.getStart().getLine(), range.getStart().getCharacter(), range.getEnd().getLine(), range.getEnd().getCharacter());
                    }
                } else {
                    activity().openFileAndSelect(file, range);
                }
                success = true;
            }
        }
        
        result.setSuccess(success);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public void telemetryEvent(Object p1) {
        LOG.info("telemetryEvent: ", gson.toJson(p1));
    }
    
    private Location asLocation(LocationLink link) {
        if(link == null || link.getTargetRange() == null || link.getTargetUri() == null) return null;
        final Location location = new Location();
        location.setUri(link.getTargetUri());
        location.setRange(link.getTargetRange());
        return location;
    }

    private List<DiagnosticGroup> mapAsGroup(Map<File, List<Diagnostic>> diags) {
        List<DiagnosticGroup> groups = new ArrayList<>();
        if(diags == null || diags.size() <= 0)
            return groups;
        for(File file : diags.keySet()) {
            List<Diagnostic> fileDiags = diags.get(file);
            if(fileDiags == null || fileDiags.size() <= 0)
                continue;
            DiagnosticGroup group = new DiagnosticGroup(R.drawable.ic_language_java, file, fileDiags);
            groups.add(group);
        }
        return groups;
    }
    
    public DiagnosticsAdapter newDiagnosticsAdapter() {
        return new DiagnosticsAdapter(mapAsGroup(this.diagnostics), activity());
    }

    /**
     * Reports connection progress
     */
    protected abstract void connectionReport(String message);

    /**
     * Called when there was an error connecting to server.
     */
    protected abstract void connectionError(Throwable th);

    public static interface StarterListener {
        void startServer();
    }

    public static interface OnConnectedListener {
        void onConnected(IDELanguageServer server);
    }
}
