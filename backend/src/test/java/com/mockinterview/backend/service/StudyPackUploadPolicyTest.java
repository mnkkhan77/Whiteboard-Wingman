package com.mockinterview.backend.service;

import com.mockinterview.backend.config.TierProperties;
import com.mockinterview.backend.config.TierProperties.TierLimits;
import com.mockinterview.backend.entity.Tier;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.exception.StudyPackUploadException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tier gate for uploads (docs/study-packs-contract.md "Tiers" / "Upload error codes"), tested
 * directly against StudyPackService.checkUpload — no Spring context, no storage.
 */
class StudyPackUploadPolicyTest {

    private static final TierLimits FREE = new TierLimits(10L * 1024 * 1024, 50, 3, 300, false, List.of("pdf"));
    private static final TierLimits PRO = new TierLimits(50L * 1024 * 1024, 300, 20, 2000, true, List.of("pdf", "docx"));
    private static final TierLimits MAX = new TierLimits(200L * 1024 * 1024, 1000, -1, 6000, true,
            List.of("pdf", "docx", "pptx", "png", "jpg", "jpeg"));

    private static User user(Tier tier, boolean guest) {
        User user = new User();
        user.setId(1L);
        user.setTier(tier);
        user.setGuest(guest);
        return user;
    }

    private static void assertRejected(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(StudyPackUploadException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getCode()).isEqualTo(code);
                });
    }

    @Test
    void acceptsAValidPdfForAFreeUserUnderEveryLimit() {
        assertThatCode(() -> StudyPackService.checkUpload(user(Tier.FREE, false), FREE, 2, 1000, "pdf",
                FileTypeSnifferTest.PDF)).doesNotThrowAnyException();
    }

    @Test
    void rejectsGuestsBeforeAnyOtherCheck() {
        assertRejected(() -> StudyPackService.checkUpload(user(Tier.FREE, true), FREE, 99, 0, "exe", new byte[0]),
                HttpStatus.FORBIDDEN, StudyPackUploadException.GUEST_UPLOAD_NOT_ALLOWED);
    }

    @Test
    void rejectsOnceThePackCountReachesTheTierLimit() {
        assertRejected(() -> StudyPackService.checkUpload(user(Tier.FREE, false), FREE, 3, 1000, "pdf",
                FileTypeSnifferTest.PDF), HttpStatus.FORBIDDEN, StudyPackUploadException.PACK_LIMIT_REACHED);
    }

    @Test
    void maxPacksMinusOneMeansUnlimited() {
        assertThatCode(() -> StudyPackService.checkUpload(user(Tier.MAX, false), MAX, 10_000, 1000, "pdf",
                FileTypeSnifferTest.PDF)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnEmptyFile() {
        assertRejected(() -> StudyPackService.checkUpload(user(Tier.FREE, false), FREE, 0, 0, "pdf", new byte[0]),
                HttpStatus.BAD_REQUEST, StudyPackUploadException.EMPTY_FILE);
    }

    @Test
    void rejectsAFileOverTheTierSizeLimitButAllowsExactlyTheLimit() {
        assertRejected(() -> StudyPackService.checkUpload(user(Tier.FREE, false), FREE, 0, FREE.maxFileBytes() + 1,
                "pdf", FileTypeSnifferTest.PDF), HttpStatus.PAYLOAD_TOO_LARGE, StudyPackUploadException.FILE_TOO_LARGE);
        assertThatCode(() -> StudyPackService.checkUpload(user(Tier.FREE, false), FREE, 0, FREE.maxFileBytes(),
                "pdf", FileTypeSnifferTest.PDF)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnExtensionTheTierDoesNotAllow() {
        assertRejected(() -> StudyPackService.checkUpload(user(Tier.FREE, false), FREE, 0, 1000, "docx",
                FileTypeSnifferTest.ZIP), HttpStatus.BAD_REQUEST, StudyPackUploadException.UNSUPPORTED_FORMAT);
        assertThatCode(() -> StudyPackService.checkUpload(user(Tier.PRO, false), PRO, 0, 1000, "docx",
                FileTypeSnifferTest.ZIP)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAFileWithNoExtension() {
        assertRejected(() -> StudyPackService.checkUpload(user(Tier.MAX, false), MAX, 0, 1000, "",
                FileTypeSnifferTest.PDF), HttpStatus.BAD_REQUEST, StudyPackUploadException.UNSUPPORTED_FORMAT);
    }

    @Test
    void rejectsARenamedFileWhoseBytesDoNotMatchItsExtension() {
        assertRejected(() -> StudyPackService.checkUpload(user(Tier.MAX, false), MAX, 0, 1000, "pdf",
                FileTypeSnifferTest.PNG), HttpStatus.BAD_REQUEST, StudyPackUploadException.UNSUPPORTED_FORMAT);
    }

    @Test
    void tierPropertiesResolveEachTierAndNormalizeExtensions() {
        TierProperties props = new TierProperties(FREE, PRO,
                new TierLimits(1, 1, -1, 1, true, List.of("PDF", "Png")));
        assertThat(props.forTier(Tier.FREE)).isSameAs(FREE);
        assertThat(props.forTier(Tier.PRO)).isSameAs(PRO);
        assertThat(props.forTier(Tier.MAX).allowedExtensions()).containsExactly("pdf", "png");
        assertThat(props.forTier(Tier.MAX).allowsExtension("PNG")).isTrue();
        assertThat(props.forTier(Tier.MAX).unlimitedPacks()).isTrue();
    }

    @Test
    void fileNameIsReducedToItsLastPathSegmentAndExtensionIsLowerCased() {
        assertThat(StudyPackService.sanitizeFileName("C:\\Users\\me\\Notes.PDF")).isEqualTo("Notes.PDF");
        assertThat(StudyPackService.sanitizeFileName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(StudyPackService.sanitizeFileName(null)).isEqualTo("upload");
        assertThat(StudyPackService.extensionOf("Notes.PDF")).isEqualTo("pdf");
        assertThat(StudyPackService.extensionOf("README")).isEmpty();
        assertThat(StudyPackService.extensionOf("trailing.")).isEmpty();
    }
}
