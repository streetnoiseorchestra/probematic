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
