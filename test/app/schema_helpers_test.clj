(ns app.schema-helpers-test
  (:require
   [app.schema-helpers :refer [DurationSchema EmailAddress InstSchema InstantSchema NonBlankString interval?]]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]
   [malli.transform :as mt]
   [tick.core :as t]))

(deftest email-schema-test
  (is (= "hello@world.com" (m/validate EmailAddress "hello@world.com")))
  (is (= "hello@world.com" (m/decode EmailAddress "HeLLo@world.COM" mt/string-transformer)))
  (is (not (m/validate EmailAddress "hellp@world@"))))

(deftest duration-schema test
  (is (m/validate DurationSchema (t/new-duration 30 :minutes)) "a duration")
  (is (not (m/validate DurationSchema (t/inst))) "an instant is not a duration")
  (is (= "PT30M" (m/encode DurationSchema (t/new-duration 30 :minutes) mt/string-transformer)) "duration encoding")
  (is (= (t/new-duration 30 :minutes) (m/decode DurationSchema "PT30M" mt/string-transformer)) "duration decoding"))

(deftest date-time-malli-schemas
  (is (m/validate InstantSchema (t/instant)) "an instant")
  (is (m/validate InstSchema (t/inst)) "an inst")
  (is (not  (m/validate InstantSchema (t/inst))) "an inst is not an instant")
  (is (m/validate InstSchema (t/instant)) "an instant is an inst")
  (is (= {:beginning #time/instant"2020-01-01T10:00:00Z", :ends nil}
         (m/decode interval? {:beginning "2020-01-01T10:00:00Z" :ends nil} mt/json-transformer))))

(deftest non-empty-string-test
  (is  (m/validate NonBlankString "foo") "foo is not blank")
  (is  (m/validate NonBlankString "  trailing and leading whitespace  ") "a non-blank string with trailing and leading whitespace isn't blank")
  (is  (not (m/validate NonBlankString "" "an empty string is blank")))
  (is  (not (m/validate NonBlankString " " "a string containing spaces is blank")))
  (is  (not (m/validate NonBlankString "  ")) "a tab character is considered blank")
  (is  (not (m/validate NonBlankString "\n")) "a new line is considered blank")
  (is  (not (m/validate NonBlankString "\r")) "a carriage return is considered blank")
  (is  (not (m/validate NonBlankString "   \n")) "mixed whitespace is considered blank")
  (is (= (m/encode NonBlankString "foo" mt/string-transformer) "foo"))
  (is (= (m/decode NonBlankString "foo" mt/string-transformer) "foo")))
