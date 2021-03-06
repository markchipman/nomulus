// Copyright 2018 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.reporting.spec11;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.testing.JUnitBackports.assertThrows;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import google.registry.gcs.GcsUtils;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.testing.TestDataHelper;
import google.registry.util.Retrier;
import google.registry.util.SendEmailService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import org.joda.time.YearMonth;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link Spec11EmailUtils}. */
@RunWith(JUnit4.class)
public class Spec11EmailUtilsTest {

  private static final int RETRY_COUNT = 2;

  private SendEmailService emailService;
  private Spec11EmailUtils emailUtils;
  private GcsUtils gcsUtils;
  private ArgumentCaptor<Message> gotMessage;

  @Before
  public void setUp() {
    emailService = mock(SendEmailService.class);
    when(emailService.createMessage())
        .thenAnswer((args) -> new MimeMessage(Session.getInstance(new Properties(), null)));

    gcsUtils = mock(GcsUtils.class);
    when(gcsUtils.openInputStream(
            new GcsFilename("test-bucket", "icann/spec11/2018-06/SPEC11_MONTHLY_REPORT")))
        .thenAnswer(
            (args) ->
                new ByteArrayInputStream(
                    loadFile("spec11_fake_report").getBytes(StandardCharsets.UTF_8)));

    gotMessage = ArgumentCaptor.forClass(Message.class);

    emailUtils =
        new Spec11EmailUtils(
            emailService,
            new YearMonth(2018, 6),
            "my-sender@test.com",
            "my-receiver@test.com",
            "test-bucket",
            "icann/spec11/2018-06/SPEC11_MONTHLY_REPORT",
            gcsUtils,
            new Retrier(new FakeSleeper(new FakeClock()), RETRY_COUNT));
  }

  @Test
  public void testSuccess_emailSpec11Reports() throws MessagingException, IOException {
    emailUtils.emailSpec11Reports();
    // We inspect individual parameters because Message doesn't implement equals().
    verify(emailService, times(3)).sendMessage(gotMessage.capture());
    List<Message> capturedMessages = gotMessage.getAllValues();
    validateMessage(
        capturedMessages.get(0),
        "my-sender@test.com",
        "a@fake.com",
        "Google Registry Monthly Threat Detector [2018-06]",
        "Hello registrar partner,\n"
            + "We have detected problems with the following domains:\n"
            + "a.com - MALWARE\n"
            + "At the moment, no action is required. This is purely informatory."
            + "Regards,\nGoogle Registry\n");
    validateMessage(
        capturedMessages.get(1),
        "my-sender@test.com",
        "b@fake.com",
        "Google Registry Monthly Threat Detector [2018-06]",
        "Hello registrar partner,\n"
            + "We have detected problems with the following domains:\n"
            + "b.com - MALWARE\n"
            + "c.com - MALWARE\n"
            + "At the moment, no action is required. This is purely informatory."
            + "Regards,\nGoogle Registry\n");
    validateMessage(
        capturedMessages.get(2),
        "my-sender@test.com",
        "my-receiver@test.com",
        "Spec11 Pipeline Success 2018-06",
        "Spec11 reporting completed successfully.");
  }

  @Test
  public void testFailure_tooManyRetries_emailsAlert() throws MessagingException, IOException {
    Message throwingMessage = mock(Message.class);
    doThrow(new MessagingException("expected")).when(throwingMessage).setSubject(any(String.class));
    // Only return the throwingMessage enough times to force failure. The last invocation will
    // be for the alert e-mail we're looking to verify.
    when(emailService.createMessage())
        .thenAnswer(
            new Answer<Message>() {
              private int count = 0;

              @Override
              public Message answer(InvocationOnMock invocation) {
                if (count < RETRY_COUNT) {
                  count++;
                  return throwingMessage;
                } else if (count == RETRY_COUNT) {
                  return new MimeMessage(Session.getDefaultInstance(new Properties(), null));
                } else {
                  assertWithMessage("Attempted to generate too many messages!").fail();
                  return null;
                }
              }
            });
    RuntimeException thrown =
        assertThrows(RuntimeException.class, () -> emailUtils.emailSpec11Reports());
    assertThat(thrown).hasMessageThat().isEqualTo("Emailing spec11 report failed");
    assertThat(thrown)
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo("javax.mail.MessagingException: expected");
    // We should have created RETRY_COUNT failing messages and one final alert message
    verify(emailService, times(RETRY_COUNT + 1)).createMessage();
    // Verify we sent an e-mail alert
    verify(emailService).sendMessage(gotMessage.capture());
    validateMessage(
        gotMessage.getValue(),
        "my-sender@test.com",
        "my-receiver@test.com",
        "Spec11 Emailing Failure 2018-06",
        "Emailing spec11 reports failed due to expected");
  }

  @Test
  public void testSuccess_sendAlertEmail() throws MessagingException, IOException {
    emailUtils.sendAlertEmail("Spec11 Pipeline Alert: 2018-06", "Alert!");
    verify(emailService).sendMessage(gotMessage.capture());
    validateMessage(
        gotMessage.getValue(),
        "my-sender@test.com",
        "my-receiver@test.com",
        "Spec11 Pipeline Alert: 2018-06",
        "Alert!");
  }

  private void validateMessage(
      Message message, String from, String recipient, String subject, String body)
      throws MessagingException, IOException {
    assertThat(message.getFrom()).asList().containsExactly(new InternetAddress(from));
    assertThat(message.getAllRecipients())
        .asList()
        .containsExactly(new InternetAddress(recipient));
    assertThat(message.getSubject()).isEqualTo(subject);
    assertThat(message.getContentType()).isEqualTo("text/plain");
    assertThat(message.getContent().toString()).isEqualTo(body);
  }

  /** Returns a {@link String} from a file in the {@code spec11/testdata/} directory. */
  public static String loadFile(String filename) {
    return TestDataHelper.loadFile(Spec11EmailUtils.class, filename);
  }
}
