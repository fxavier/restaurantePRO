-- R6 S73 (D187, review 6): the print bridge that claimed a job (its store device id). Only that bridge may report the
-- job's outcome; a claim by the in-process sender of an installation without bridges leaves it NULL. Set at each claim.
ALTER TABLE production.print_job
    ADD COLUMN claimed_by UUID;
