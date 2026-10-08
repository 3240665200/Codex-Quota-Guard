alter table teams add column total_token_limit bigint;
alter table teams add constraint teams_total_token_limit_valid
    check (total_token_limit is null or total_token_limit >= 0);
