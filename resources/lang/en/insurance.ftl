### Insurance

title = Insurance & Instruments
toolbar-label = Insurance navigation
new-policy = New Insurance Policy
create-title = New Insurance Policy
create-subtitle = Enter the details for the new policy.
default-policy-name = Band instruments { $start-year }–{ $end-year }
policy-details = Policy details
name = Policy name
effective-at = Effective from
effective-until = Effective until
premium-base-factor = Base premium factor
error-invalid-date = Enter a valid date.
error-effective-until-before-effective-at = The end date must be after the start date.
error-invalid-premium-factor = Enter a non-negative premium factor.
send-changes = Send changes
changes-title = Notify the insurer about changes
changes-subtitle = Review the message and its spreadsheet attachments before confirming this policy.
message-details = Message details
recipient = Recipient
subject = Subject
message = Message
attachments = Attachments
attachments-subtitle = Preview either spreadsheet before sending it.
attachment-filename = Attachment filename
new-instruments = New instruments
changed-and-removed-instruments = Changed and removed instruments
preview = Preview
confirm-and-send = Confirm and send
confirm-skip-send = Confirm without sending
confirm-send-title = Send this message?
confirm-send-body = The message and both spreadsheet attachments will be sent, then the policy changes will be confirmed.
confirm-without-sending-title = Confirm without sending?
confirm-without-sending-body = The policy changes will be confirmed without sending a message to the insurer.
changes-email-subject = Update { $policy-number }
changes-email-body =
    Dear { $recipient-title } { $recipient-name },

    Attached are the latest changes to our band instrument insurance policy.
    One spreadsheet contains new instruments and the other contains changed or removed instruments.

    Please contact me if you need any additional information.

    Kind regards,
    { $sender-name }
review = Review
workbench = Table
add-coverage = Add Instrument
policy-settings = Policy Settings
manage-surveys = Manage surveys
pending-coverage-reviews = Coverage reviews
# $count (Number) - Instruments left to review. $total (Number) - Total instruments. $policy (String) - Policy name. $date (String) - Localized closing date and time.
survey-response-detail =
    { $count ->
        [0] Ready to finish
        [one] One of { $total } instruments needs review
       *[other] { $count } of { $total } instruments need review
    } · { $policy } · closes { $date }
survey-admin-title = Coverage surveys
survey-admin-subtitle = Ask every member to review the instruments covered by this policy.
start-survey = Start survey
survey-details-title = Survey details
survey-details-subtitle = Members can respond until the closing date or until the insurance team closes the survey.
survey-name = Survey name
survey-name-hint = Give this review a short, recognizable name.
survey-closes-at = Closes at
survey-closes-at-hint = The date and time after which members should no longer respond.
survey-name-required = Enter a survey name.
survey-closes-at-invalid = Enter a valid closing date and time.
survey-closes-at-future = Choose a closing date in the future.
survey-open = Open
survey-no-open-title = No open coverage survey
survey-no-open-body = Start a survey when members should check their currently insured instruments.
survey-responses-title = Member responses
survey-responses-subtitle = Track progress and correct a completion status when necessary.
survey-reviewed = Reviewed
survey-status = Status
survey-complete = Complete
survey-incomplete = Incomplete
survey-progress = { $completed } of { $total }
survey-mark-complete = Mark complete
survey-mark-incomplete = Mark incomplete
survey-no-responses = This survey has no member responses.
survey-send-reminders = Send reminders
survey-send-reminders-title = Send coverage survey reminders?
survey-send-reminders-body = Every member who has not completed this survey will receive an email reminder.
survey-reminders-confirm = Send reminders
survey-reminders-sent =
    { $count ->
        [one] One reminder was sent.
       *[other] { $count } reminders were sent.
    }
survey-reminders-empty = Everyone has already completed this survey.
survey-reminders-failed = The survey reminders could not be sent. Check the outgoing email queue before trying again.
survey-close = Close survey
survey-close-title = Close this coverage survey?
survey-close-body = Members will no longer be able to change their responses.
survey-close-confirm = Close survey
survey-created = The coverage survey was started.
survey-updated = The survey details were saved.
survey-closed = The coverage survey was closed.
survey-closed-title = Closed surveys
survey-closed-subtitle = Earlier surveys for this policy.
survey-closed-on = Closed { $date }
survey-error-not-found = This policy or survey could not be found.
survey-error-not-allowed = Only members of the insurance team can manage coverage surveys.
survey-error-open-exists = Close the current survey before starting another one.
survey-error-closed = This survey is already closed.
survey-error-expired = This survey has reached its closing date.
survey-error-no-members = A survey cannot be started because there are no members to invite.
add-coverage-title = Add Instrument Coverage
instrument-step = Instrument
photos-step = Photos
coverage-step = Coverage
edit-coverage = Edit Instrument Coverage

### Payment notifications

request-payments-title = Request insurance payments
request-payments-subtitle = Select the members who should receive a payment request for privately insured instruments.
send-payment-notifications = Send notifications
send-payment-notifications-failed = The payment notifications could not be completed. Check the policy ledger and outgoing email before trying again.
payment-error-not-allowed = Only members of the insurance team can send payment notifications.
payment-members-title = Members to notify
payment-members-subtitle = Members with calculable private-instrument costs are selected by default.
member = Member
private-instruments = Private instruments
total = Total
cost-unavailable = Cost unavailable
select-member-for-payment = Select { $member-name } for a payment request
select-payment-members = Select at least one member to notify.
payments-missing-category-factors = Costs are unavailable for instruments in these categories because their policy factors are missing: { $category-names }.
no-private-payments-title = No payments to request
no-private-payments = No private instrument payments need to be requested for this policy.
payment-email-preview-title = Email preview
payment-email-preview-subtitle = This example uses the first selected member’s instruments and total.
payment-notifications-sent-title = Notifications sent
payment-notifications-sent =
    { $count ->
        [one] One payment notification was sent.
       *[other] { $count } payment notifications were sent.
    }

### Member coverage review

coverage-review = Coverage review
review-progress = Coverage review progress
review-item-step = Item { $number }
review-used-at-gig = Was this used at a Street Noise gig in the past year?
review-yes = Yes
review-no = No
review-keep-insured = Do you want to keep this instrument insured?
review-confirm-remove = Are you sure you want to remove its insurance coverage?
review-confirm-remove-band-hint = The band currently pays for this coverage because this is a band instrument.
review-keep-coverage = No, keep the coverage
review-remove-coverage = Yes, remove it
review-pay-to-keep = Do you want to pay to keep this instrument insured?
review-pay-to-keep-hint = Since this was not used at a gig in the past year, the band can no longer pay for it.
review-private-cost = Keeping it insured will cost approximately { $cost } per year.
review-stop-coverage = No, stop the coverage
review-pay = Yes, I’ll pay
review-confirm-private-cost = Keeping this instrument insured will cost approximately { $cost } per year. Is that okay?
review-data-correct = Is all the information shown here still correct?
review-data-correct-hint = Check the insured value and coverage types in particular.
review-invalid-transition = That answer is not valid for the current review question.
review-not-available-title = No coverage review available
review-not-available = There is no coverage review available for you on this policy.
review-cannot-finish = Finish reviewing the remaining instruments before completing this coverage review.
review-no-items-title = No instruments to review
review-no-items-body = You do not currently have any insured instruments in this review. You can add one or finish the review.
review-finish = I’m finished
review-closed-title = This coverage review is closed
review-closed-body = Answers can no longer be changed because the insurance team has closed this review.
review-contact-team = Contact the insurance team if something still needs to change.
review-complete-title = Coverage review complete
review-complete-body = Thanks. The insurance team has received your responses.
review-good-job = Good job
review-completed-progress =
    { $completed ->
        [one] You reviewed one instrument. { $remaining } remain.
       *[other] You reviewed { $completed } instruments. { $remaining } remain.
    }
review-keep-going = Keep going
review-correct-data-title = Correct the instrument information
review-correct-data-body = Update anything that has changed, then save to finish reviewing this instrument.
review-coverage-body = Check the insured value and coverage types.
ownership = Ownership
ownership-band = Band instrument
ownership-private = Private instrument
annual-cost = Estimated annual cost
item-count = Number of items
item-count-hint = How many identical items are being insured (for example, one trumpet or four drumsticks)?
value = Insured value
value-hint = For multiple identical insured items, enter the value of one item.
coverage-types = Coverage types
instrument-coverage = Instrument coverage
insurer-id = Insurer ID
insurer-id-hint = Enter the identifier assigned by the insurer, if available.
photos = Photos
no-photos = No photos have been uploaded yet.
photo-upload = Instrument photos
photo-upload-subtitle = Add clear photos from several angles.
upload-drop-label = Choose photos to upload
upload-help = PNG, JPG, or GIF up to 10 MB.
upload-progress = Uploading photos…
upload-complete = Upload complete.
upload-error = Upload failed.
