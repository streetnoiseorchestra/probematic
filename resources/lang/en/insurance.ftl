### Insurance

title = Insurance & Instruments
toolbar-label = Insurance navigation
dashboard-todos = Insurance team tasks
new-policy = New Insurance Policy
create-title = New Insurance Policy
create-subtitle = Enter the details for the new policy.
default-policy-name = Band instruments { $start-year }–{ $end-year }
policy-details = Policy details
name = Policy name
effective-at = Effective from
effective-until = Effective until
premium-base-factor = Base premium factor
policies = Policies
insurance-policy = Insurance policy
currency = Currency
premium-factor = Premium factor
cost = Cost
coverage-name = Coverage name
coverage-type-description = Description
category-factors = Category factors
value-abbrev = Insured value
coverage-status = Workflow status
coverage-status-needs-review = Todo
coverage-status-reviewed = Reviewed
coverage-status-active = Active
coverage-change-status = Change status
coverage-change-added = Added
coverage-change-modified = Modified
coverage-change-removed = Removed
coverage-change-none = No changes
send-changes-disabled-hint = Changes cannot be sent while there are open TODOs.
total-needs-review-tooltip = Total instruments that need review
total-total-changed-tooltip = Total instruments that have changed
total-total-new-tooltip = Total instruments that have been added
total-total-removed-tooltip = Total instruments that have been removed
error-invalid-date = Enter a valid date.
error-effective-until-before-effective-at = The end date must be after the start date.
error-invalid-premium-factor = Enter a non-negative premium factor.
error-edit-frozen-policy = Instruments and their coverage cannot be updated unless the policy is in draft status.
# $field (String) - Localized label of the field containing the invalid number.
error-invalid-number = { $field } must be a whole number greater than zero.
error-invalid-coverage-type = Choose valid coverage types.
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
instrument-insurance = Instrument insurance
# $count (Number) - Instruments left to review. $total (Number) - Total instruments.
review-dashboard-progress =
    { $count ->
        [0] Ready to finish
        [one] 1 out of { $total } instruments is waiting for you
       *[other] { $count } out of { $total } instruments are waiting for you
    }
# Shown immediately before a relative time such as “in 4 days”.
review-dashboard-due = Due
# $name (String) - The member's display name.
review-dashboard-greeting = { $name }, it’s time to review your insured instruments.
# $minutes (Number) - Estimated whole minutes needed to finish the review.
review-dashboard-estimate = About { $minutes } min.
review-dashboard-start = Start review
survey-admin-title = Coverage surveys
survey-admin-subtitle = Ask every member to review the instruments covered by this policy.
start-survey = Start survey
survey-details-title = Survey details
survey-details-subtitle = Members can respond until the closing date or until the insurance team closes the survey.
survey-closes-at = Closes at
survey-closes-at-hint = The date and time after which members should no longer respond.
survey-closes-at-invalid = Enter a valid closing date and time.
survey-closes-at-future = Choose a closing date in the future.
survey-closes-at-saving = Saving…
survey-closes-at-saved = Saved
survey-closes-at-save-failed = Could not save
survey-open = Open
survey-no-open-title = No open coverage survey
survey-no-open-body = Start a survey when members should check their currently insured instruments.
survey-responses-title = Member responses
survey-responses-subtitle = Track progress and correct a completion status when necessary.
survey-responses-table-caption = Member responses to this insurance survey
survey-response-actions = Response actions
# Accessible label for the segmented response filter.
survey-filter-label = Filter member responses
# $count (Number) - All member responses.
survey-filter-all = All ({ $count })
# $count (Number) - Member responses not yet marked complete.
survey-filter-incomplete = Incomplete ({ $count })
# $count (Number) - Member responses marked complete.
survey-filter-completed = Complete ({ $count })
survey-filter-empty-all = No member responses yet.
survey-filter-empty-incomplete = Everyone has completed this survey.
survey-filter-empty-completed = No completed responses yet.
survey-reviewed = Reviewed
survey-status = Status
survey-complete = Complete
survey-incomplete = Incomplete
survey-progress = { $completed } of { $total }
# Summary of member completion on an open insurance survey.
# $completed (Number) - Member responses marked complete. $total (Number) - All member responses.
survey-progress-summary = { $completed } of { $total } member responses complete.
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
survey-created = The coverage survey was started. No reminders were sent. Click “Send reminders” to notify members.
survey-closed = The coverage survey was closed.
survey-more-actions = More survey actions
survey-closed-title = Closed surveys
survey-closed-subtitle = Earlier surveys for this policy.
# $created (String) - Localized start date and time. $ended (String) - Localized manual closing date and time.
survey-history-closed = Started { $created } · Closed { $ended }
# $created (String) - Localized start date and time. $ended (String) - Localized expiry date and time.
survey-history-expired = Started { $created } · Expired { $ended }
survey-error-not-found = This policy or survey could not be found.
survey-error-not-allowed = Only members of the insurance team can manage coverage surveys.
survey-error-open-exists = Close the current survey before starting another one.
survey-error-closed = This survey is already closed.
survey-error-expired = This survey has reached its closing date.
survey-error-no-members = A survey cannot be started because there are no members to invite.
survey-email-title = SNO Insurance Time!
survey-email-subject = It’s SNO Insurance Time!
survey-email-intro = It’s time to review your insured instruments/items.
survey-email-add-instruments = Or add new ones! It only takes a few minutes.
# $member-name (String) - Display name of the member with the most instruments. $count (Number) - Instruments assigned to that member.
survey-email-add-instruments-many = Or add new ones! It only takes a few minutes (unless you’re { $member-name }, who has { $count } to review!)
# $closes-at (String) - Formatted closing date and time. $days (Number) - Whole days until the survey closes.
survey-email-deadline = You have until { $closes-at } to complete the survey (that’s only { $days } days!).
survey-email-start = Start review
email-team-name = Insurance team, Street Noise Orchestra

### Instrument coverage creation

add-coverage-title = Add Instrument Coverage
# $policy-name (String) - Name of the policy that will cover the new instrument.
add-coverage-subtitle = Add an instrument and register it for { $policy-name }.
coverage-create-steps = Coverage creation steps
instrument-step = Instrument
photos-step = Photos
coverage-step = Coverage
edit-coverage = Edit Instrument Coverage
add-coverage-separate-warning = A separate form must be filled out for each instrument and accessory.
# $policy-name (String) - Name of the policy whose coverage details are being entered.
coverage-for = The following information is for the insurance policy “{ $policy-name }”.
no-coverage-types = No coverage types configured.

### Policy dashboard

policy-status-active = Active
policy-status-sent = Sent
policy-status-draft = Draft
dashboard-continue-reviewing = Review
dashboard-review-status = Review status
# $handled (Number) - Instruments already handled. $total (Number) - All instruments.
dashboard-review-complete = { $handled } of { $total } handled
dashboard-health-checklist = Health checklist
dashboard-health-checklist-subtitle = Required data before sending changes.
# $passed (Number) - Successful checks. $total (Number) - All checks.
dashboard-health-checks-complete = { $passed } of { $total } checks passed
dashboard-coverage-mix = Coverage mix
dashboard-coverage-mix-subtitle = Band and private instruments in this policy.
dashboard-recent-changes = Recent changes
dashboard-recent-changes-subtitle = Current coverage change flags for this policy.
dashboard-no-recent-changes = No changed, new, or removed instruments.
dashboard-policy-details = Policy details
dashboard-policy-status = Policy status
dashboard-total-instruments = Total instruments
dashboard-total-instruments-tooltip = Number of covered instruments in this policy.
dashboard-total-insured-value = Total insured value
dashboard-total-insured-value-tooltip = Sum of insured values for all covered instruments.
dashboard-policy-cost = Policy cost
dashboard-policy-cost-tooltip = Estimated policy cost from current coverage values and premium factors.
dashboard-missing-photos = Missing photos
dashboard-missing-insurer-ids = Missing Harmonia IDs
dashboard-band-instruments = Band instruments
dashboard-private-instruments = Private instruments
### Policy settings

policy-settings-category-factors-subtitle = Factors by instrument category.
policy-settings-coverage-types-subtitle = Insurance options available on this policy.
policy-settings-add-coverage-type = Add coverage type
policy-settings-add-category-factor = Add category factor
policy-settings-category-factor-create-disabled-tooltip = Every instrument category already has a category factor.
# $coverage-type-name (String) - Name of the coverage type being deleted.
policy-settings-coverage-type-delete-confirm = Delete coverage type { $coverage-type-name }?
# $count (Number) - Number of current coverages using the coverage type.
policy-settings-coverage-type-in-use =
    { $count ->
        [one] This coverage type is used by one current coverage and cannot be deleted.
       *[other] This coverage type is used by { $count } current coverages and cannot be deleted.
    }
# $category-name (String) - Name of the instrument category whose factor is being deleted.
policy-settings-category-factor-delete-confirm = Delete category factor for { $category-name }?
# $count (Number) - Number of current coverages using the category factor.
policy-settings-category-factor-in-use =
    { $count ->
        [one] This category factor is used by one current coverage and cannot be deleted.
       *[other] This category factor is used by { $count } current coverages and cannot be deleted.
    }
policy-settings-current-cost = Current estimated cost
policy-settings-current-totals = Current totals
policy-settings-current-totals-subtitle = Safe totals from the current configuration.
policy-settings-error-policy-not-found = Policy not found.
policy-settings-error-not-allowed = You are not allowed to change policy settings.
policy-settings-error-frozen-policy = This policy is not draft, so settings cannot be changed.
policy-settings-error-invalid-date = Enter a valid date.
policy-settings-error-invalid-premium-factor = Enter a valid non-negative premium factor.
policy-settings-error-invalid-currency = Choose a supported currency.
policy-settings-error-effective-until-before-effective-at = The end date must be after the start date.
policy-settings-edit-coverage-type = Edit coverage type
policy-settings-edit-category-factor = Edit category factor
policy-settings-error-coverage-type-in-use = Coverage type is still used.
policy-settings-error-coverage-type-not-found = Coverage type not found for this policy.
policy-settings-error-duplicate-coverage-type-name = A coverage type with this name already exists for this policy.
policy-settings-error-category-factor-in-use = Category factor is still used.
policy-settings-error-category-factor-not-found = Category factor not found for this policy.
policy-settings-error-category-not-found = Category not found.
policy-settings-error-duplicate-category-factor = A category factor for this category already exists for this policy.
policy-settings-missing-category-factors-title = Missing category factors
# $category-names (String) - Comma-separated names of categories without factors.
policy-settings-missing-category-factors-body = Covered instruments use categories without category factors: { $category-names }.
policy-settings-no-category-factors = No category factors configured.
policy-settings-policy-details-subtitle = Edit policy metadata used for cost calculations.
policy-settings-read-only-title = Settings are read-only
policy-settings-status-read-only = Policy status is read-only here.
# $policy-name (String) - Name of the policy being configured.
policy-settings-subtitle = Configure settings for { $policy-name }.
policy-settings-usage = Usage

### Coverage workbench

workbench-title = Coverage workbench
workbench-view = View
workbench-view-all = All coverages
workbench-view-todo = Todo
workbench-view-missing-id = Missing Harmonia IDs
workbench-view-missing-photos = Missing photos
workbench-view-private = Private instruments
workbench-view-changed = Changed
workbench-view-new = New
workbench-view-removed = Removed
workbench-ownership = Ownership
workbench-ownership-all = All
workbench-ownership-band = Band
workbench-ownership-private = Private
workbench-member-search-placeholder = Search by name, nickname, username, or email
workbench-search = Search
workbench-selected = selected
workbench-photos = Photos
workbench-workflow-status = Workflow status
workbench-change-status = Change status
workbench-status = Status
workbench-missing = Missing
workbench-missing-photos = Missing photos
workbench-table-settings = Table settings
workbench-columns = Columns
# $field (String) - Localized name of the field being filtered.
workbench-filter-by = Filter by: { $field }
workbench-value-operator = Value operator
workbench-value-greater-than = is greater than
workbench-value-less-than = is less than
workbench-value-equal-to = is equal to
workbench-value-between = is between
workbench-value-min = Minimum
workbench-value-max = Maximum
workbench-pagination = Pagination
# $range-start (Number) - First result on the page.
# $range-end (Number) - Last result on the page.
# $total-results (Number) - Total matching results.
workbench-pagination-summary = { $range-start }–{ $range-end } of { $total-results } results
workbench-rows-per-page = Rows per page
workbench-and = and
workbench-select-row = Select coverage
workbench-expand-all = Expand all
workbench-collapse-all = Collapse all
workbench-read-only = Bulk actions are read-only.
workbench-mark-workflow = Mark Workflow
workbench-set-change = Set Change
workbench-deselect-all = Deselect All
workbench-empty-title = No matching coverages
workbench-empty-body = Try another view or filter.
workbench-error-empty-selection = Select at least one coverage.
workbench-error-invalid-target-status = Choose a valid workflow or change status.
workbench-error-not-allowed = You are not allowed to update these coverages.
workbench-error-frozen-policy = This policy is not draft, so coverages cannot be changed.
workbench-error-coverage-not-found = Coverage not found.
workbench-error-coverage-not-in-policy = All selected coverages must belong to this policy.
workbench-group-member = Group by member

### Policy review queue and coverage history

review-queue-title = Review queue
# $policy-name (String) - Name of the policy being reviewed.
review-queue-subtitle = Review covered instruments for { $policy-name }.
review-queue-filter-needs-review = Todo
review-queue-filter-missing-insurer-id = Missing ID
# $count (Number) - Number of review items remaining in the current queue.
review-queue-items-left =
    { $count ->
        [one] { $count } item left.
       *[other] { $count } items left.
    }
review-queue-see-all-in-workbench = See all in the workbench
review-queue-queue = Queue
review-queue-empty-title = No matching coverages
review-queue-empty-body = Try another filter or return to the dashboard.
# $owner-name (String) - Name of the instrument owner.
review-queue-reviewing-owner = Owner: { $owner-name }
review-queue-approve-and-next = Approve and next
review-queue-save-and-continue = Save and continue
review-queue-skip = Skip
review-queue-not-insurance-team = Only insurance team members can update review items.
review-queue-frozen-policy = This policy is not draft, so review actions are read-only.
review-queue-error-coverage-not-found = Coverage not found.
review-queue-error-not-allowed = You are not allowed to update this review item.
review-queue-error-frozen-policy = This policy is not draft, so review items cannot be changed.
history-title = History of changes
history-subtitle-coverage = Changes to the coverage of this instrument
history-field = Field
history-before = Before
history-after = After
history-no-changes = No changes yet.
history-image-deleted = Image deleted.
history-action-added = Added
history-action-retracted = Retracted
history-action-updated = Updated
review-queue-comments = Comments
review-queue-comment-placeholder = Add a note about this item.
review-queue-add-comment = Add comment
review-queue-commented = commented
review-queue-leave-reply = Leave a reply

### Coverage type settings

coverage-type-icon = Icon
coverage-type-icon-hint = Choose the icon shown for this coverage type.
error-invalid-coverage-type-icon = Choose an icon from the list.
coverage-type-required = Required
coverage-type-required-hint = This coverage type will be added to every insured instrument.
coverage-type-add-to-band-instruments = Add to existing band instruments
coverage-type-add-to-band-instruments-hint = This optional coverage type will be added to every currently insured band instrument.
# $count (Number) - Current number of instruments that will gain the coverage type.
coverage-type-impact-confirmation =
    { $count ->
        [one] This will add the coverage type to { $count } instrument.
       *[other] This will add the coverage type to { $count } instruments.
    }
# $count (Number) - Exact number the administrator must enter to confirm the bulk change.
coverage-type-confirmation-count = Type { $count } to confirm
coverage-type-confirmation-count-hint = Enter the current number of affected instruments.
error-stale-impact-count = The number of affected instruments changed. Review the new count and enter it to confirm.

### Public coverage information

faq-coverage-types-question = What coverage types are available?
coverage-required = Required
coverage-optional = Optional
faq-coverage-types-empty = The active policy does not define any coverage types.
faq-coverage-types-summary = Required coverage types apply to every insured instrument. Optional coverage types can be selected per instrument.

### Policy exporter settings

exporter = Exporter
exporter-subtitle = Choose the spreadsheet format for this policy and map its required roles to coverage types.
exporter-none = No exporter
exporter-harmonia-v1 = Harmonia excel (v1)
exporter-role-overnight-vehicle = Overnight in a vehicle
exporter-role-unattended-building = Unattended in a locked building
exporter-role-unmapped = Choose a coverage type
error-invalid-exporter = Choose a registered exporter.
error-incomplete-exporter-mapping = Map every required exporter role.
error-invalid-exporter-mapping = Choose coverage types from this policy for the exporter roles.
error-duplicate-exporter-role = Each exporter role may be mapped only once.
exporter-not-configured-guidance = Choose and configure an exporter in the policy settings before previewing or sending spreadsheets.
exporter-unknown-guidance = The policy uses an exporter version this application does not recognize. Choose a supported version in the policy settings.
exporter-incomplete-guidance = Map every required exporter role in the policy settings before previewing or sending spreadsheets.

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
# $member-name (String) - Member receiving the payment request. $time-range (String) - Policy period covered by the request.
payment-email-subject = { $member-name } insurance costs { $time-range } SNO
payment-email-intro = Please transfer your insurance costs to Street Noise Orchestra. These are the costs for privately insured instruments for the following period:
# $member-name (String) - Member receiving the payment request.
payment-email-member-costs = Insurance costs for { $member-name }:
payment-email-bank-data = Bank details
payment-email-cost-details = For a detailed breakdown of your costs, see the following forum link.
payment-email-claim-guidance = If an insured item is damaged or lost, the attachment contains instructions and the claim form.
payment-email-contact = If you have any questions, please contact us.

### Member instrument check

review-title = Instrument check
coverage-review = Coverage review
review-progress = Instrument check progress
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
review-not-available-title = No instrument check available
review-not-available = There is no instrument check available for you right now.
review-cannot-finish = Check the remaining instruments before finishing.
review-no-items-title = No instruments to review
review-no-items-body = You do not currently have any insured instruments in this check. You can add one or finish.
review-finish = I’m finished
review-closed-title = This instrument check is closed
review-closed-body = Answers can no longer be changed because the insurance team has closed this check.
review-contact-team = Contact the insurance team if something still needs to change.
review-complete-title = All done!
review-complete-body = Thanks. The insurance team has received your responses.
review-celebrate = Celebrate!
review-celebrate-again = Why not celebrate again?
review-celebrate-feels-good = Feels good, right? One more.
review-celebrate-thanks = The insurance team thanks you.
review-good-job = Good job
review-milestone =
    { $remaining ->
        [one] Just one instrument left.
       *[other] Only { $remaining } instruments left.
    }
review-animation-lab = Animation lab (dev only)
review-animation-sequence = Full sequence
review-animation-throw = Throw card
review-animation-advance = Advance deck
review-animation-question = Question
review-animation-encouragement = Encouragement
review-animation-reset = Reset
review-card-details = Instrument details
review-correct-data-title = Correct the instrument information
review-correct-data-body = Update anything that has changed, then save to finish reviewing this instrument.
review-coverage-body = Check the insured value and coverage types.
ownership = Ownership
ownership-band = Band instrument
ownership-band-description = A band instrument is an instrument that was played at a SNO gig in the past year.
ownership-private = Private instrument
ownership-private-description = A private instrument is an instrument that the owner will insure at their own expense.
ownership-hint = Is this instrument a band instrument or private instrument?
annual-cost = Estimated annual cost
item-count = Number of items
item-count-hint = How many identical items are being insured (for example, one trumpet or four drumsticks)?
value = Insured value
value-hint = For multiple identical insured items, enter the value of one item.
insured-value-hint = The estimated market value of the item, i.e., how much it would cost to replace it if lost.
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
