package dev.miqui.messageservice;

import dev.miqui.messageservice.v1.Author;
import dev.miqui.messageservice.v1.DeleteAuthorRequest;
import dev.miqui.messageservice.v1.DeleteMessageRequest;
import dev.miqui.messageservice.v1.GetAuthorRequest;
import dev.miqui.messageservice.v1.GetAuthorResponse;
import dev.miqui.messageservice.v1.ListAuthorsRequest;
import dev.miqui.messageservice.v1.MessageSummary;
import dev.miqui.messageservice.v1.UpdateAuthorRequest;
import io.grpc.Status.Code;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorServiceTest extends GrpcTestSupport {

    private DeleteAuthorRequest delete(String id) {
        return DeleteAuthorRequest.newBuilder().setId(id).build();
    }

    @Test
    void createTrims() {
        Author author = createAuthor(" Bob ", " bob@example.com ");
        assertThat(UUID.fromString(author.getId())).isNotNull();
        assertThat(author.getName()).isEqualTo("Bob");
        assertThat(author.getEmail()).isEqualTo("bob@example.com");
        assertThat(author.hasCreatedAt()).isTrue();
    }

    @Test
    void validationCollectsAllErrors() {
        var error = rpcError(() -> createAuthor("x".repeat(51), "not-an-email"));
        assertThat(error.code()).isEqualTo(Code.INVALID_ARGUMENT);
        assertThat(error.reason()).isEqualTo("BAD_USER_INPUT");
        assertThat(error.violations()).containsExactly(
                Map.entry("name", "name cannot exceed 50 characters"),
                Map.entry("email", "email must be a valid email address"));
    }

    @Test
    void duplicateEmailIsAlreadyExists() {
        Author author = createAuthor();
        var dup = rpcError(() -> createAuthor("Other", author.getEmail()));
        assertThat(dup.code()).isEqualTo(Code.ALREADY_EXISTS);
        assertThat(dup.reason()).isEqualTo("CONFLICT");

        Author other = createAuthor("O", "o@example.com");
        var clash = rpcError(() -> authors.updateAuthor(UpdateAuthorRequest.newBuilder()
                .setId(other.getId()).setEmail(author.getEmail()).build()));
        assertThat(clash.code()).isEqualTo(Code.ALREADY_EXISTS);
    }

    @Test
    void deletingAnAuthorWithMessagesFailsUntilTheyAreGone() {
        Author author = createAuthor();
        String messageId = createMessage(author.getId()).getId();

        var blocked = rpcError(() -> authors.deleteAuthor(delete(author.getId()))); // FK RESTRICT
        assertThat(blocked.code()).isEqualTo(Code.FAILED_PRECONDITION);
        assertThat(blocked.reason()).isEqualTo("CONFLICT");

        messages.deleteMessage(DeleteMessageRequest.newBuilder().setId(messageId).build());
        authors.deleteAuthor(delete(author.getId()));
        assertThat(rpcError(() -> authors.deleteAuthor(delete(author.getId()))).code()).isEqualTo(Code.NOT_FOUND);
    }

    @Test
    void getAuthorAndIncludeMessages() {
        Author author = createAuthor();
        createMessage(author.getId(), "T");

        GetAuthorResponse plain = authors.getAuthor(GetAuthorRequest.newBuilder().setId(author.getId()).build());
        assertThat(plain.getAuthor()).isEqualTo(author);
        assertThat(plain.getMessagesList()).isEmpty();

        GetAuthorResponse detailed = authors.getAuthor(GetAuthorRequest.newBuilder()
                .setId(author.getId()).setIncludeMessages(true).build());
        // MessageSummary has no author field: no author -> messages -> author cycle.
        assertThat(detailed.getMessagesList()).extracting(MessageSummary::getTitle).containsExactly("T");

        assertThat(rpcError(() -> authors.getAuthor(GetAuthorRequest.newBuilder()
                .setId(UUID.randomUUID().toString()).build())).code()).isEqualTo(Code.NOT_FOUND);
    }

    @Test
    void updateAuthor() {
        Author author = createAuthor();
        Author renamed = authors.updateAuthor(UpdateAuthorRequest.newBuilder()
                .setId(author.getId()).setName("Renamed").build());
        assertThat(renamed.getName()).isEqualTo("Renamed");
        assertThat(renamed.getEmail()).isEqualTo(author.getEmail());

        Author noop = authors.updateAuthor(UpdateAuthorRequest.newBuilder().setId(author.getId()).build());
        assertThat(noop).isEqualTo(renamed);

        assertThat(rpcError(() -> authors.updateAuthor(UpdateAuthorRequest.newBuilder()
                .setId(UUID.randomUUID().toString()).setName("x").build())).code()).isEqualTo(Code.NOT_FOUND);
        assertThat(rpcError(() -> authors.updateAuthor(UpdateAuthorRequest.newBuilder()
                .setId(UUID.randomUUID().toString()).build())).code()).isEqualTo(Code.NOT_FOUND);
        assertThat(rpcError(() -> authors.updateAuthor(UpdateAuthorRequest.newBuilder()
                .setId(author.getId()).setName("  ").build())).code()).isEqualTo(Code.INVALID_ARGUMENT);
    }

    @Test
    void listAuthors() {
        Author author = createAuthor();
        var page = authors.listAuthors(ListAuthorsRequest.getDefaultInstance());
        assertThat(page.getTotalCount()).isEqualTo(1);
        assertThat(page.getItems(0).getId()).isEqualTo(author.getId());
        assertThat(rpcError(() -> authors.listAuthors(ListAuthorsRequest.newBuilder().setLimit(0).build())).code())
                .isEqualTo(Code.INVALID_ARGUMENT);
    }
}
