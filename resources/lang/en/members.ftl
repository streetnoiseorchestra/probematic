### Member directory and profiles

title = Members
member-count =
    { $count ->
        [one] One member
       *[other] { $count } members
    }
invite-member = Invite member
invite-description = Create a member and optionally send a SNO ID invitation.
download-contact = Download contact
directory-toolbar-label = Member directory controls
invite-toolbar-label = Member invitation controls
detail-toolbar-label = Member controls
edit-toolbar-label = Member editor controls
insurance-title = Insurance & instruments
insurance-subtitle = Instruments and other items registered with the band for insurance purposes.

### Member fields and SNO ID

name = Name
nickname = Nickname
email = E-Mail
phone = Phone
username = Username
section = Section
active-label = Active?
sno-id = SNO ID
sno-uuid = SNO UUID
sno-id-status-label = SNO ID Enabled/Disabled
sno-id-disabled-tooltip = When disabled the user cannot login to any SNO systems.
sno-id-enabled = Enabled
sno-id-disabled = Disabled
create-sno-id = Create SNO ID
create-sno-id-description = An invitation to set their password will be e-mailed to the person.

### Directory filters and invitations

filter-all = All Members
filter-active = Active Members
filter-inactive = Inactive Members
open-invitations = Open Invitations
open-invitations-subtitle = Pending invitations can be resent or deleted.
browse-empty = No members found.
browse-empty-subtitle = Try a different search phrase or filter.

### Travel discounts

oebb-discount = ÖBB Discount
travel-discounts-title = Travel Discounts
travel-discounts-subtitle = Add your ÖBB travel discounts
travel-discounts-empty = No travel discounts
travel-discount-add = Add a ÖBB discount
travel-discount-type = Discount Type
travel-discount-expiry-date = Expires On
# $date (String) - Formatted expiry date.
travel-discount-expires = Expires: { $date }
# $discount-type (String) - Name of the travel discount being deleted.
confirm-delete-travel-discount = Delete { $discount-type }?

### SNO ID invitation acceptance

invite-accept-create-title = Create SNO ID
invite-accept-create-subtitle = You're only a few steps away from your new SNO ID!
invite-accept-create-account = Create account
invite-accept-created-title = Your SNO ID was created!
invite-accept-created-subtitle = Welcome :) Please login using your new SNO ID.
