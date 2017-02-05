import { Component, ElementRef, OnInit, OnChanges, ViewChild, Inject } from "@angular/core";
import { Router } from "@angular/router";
import { Color } from "color";
import { Page } from "ui/page";
import { TextField } from "ui/text-field";
import { View } from "ui/core/view";
import { RouterExtensions } from "nativescript-angular/router"
import dialogs = require("ui/dialogs");
import { Account, EventConfiguration } from "../../shared/account/account";
import { AccountService } from "../../shared/account/account.service";
import {AccountResponse} from "../../shared/account/account";
import { BARCODE_SCANNER, BarcodeScanner, defaultScanOptions } from '../../utils/barcodescanner';

@Component({
    selector: "account-selection",
    templateUrl: "pages/account-selection/account-selection.html",
    styleUrls: ["pages/account-selection/account-selection-common.css"],
})
export class AccountSelectionComponent implements OnInit, OnChanges {
    accounts: Array<Account> = [];
    isLoading: boolean;
    private editedAccount?: Account = null;

    constructor(private router: Router, 
        private accountService: AccountService, 
        private page: Page, 
        private routerExtensions: RouterExtensions,
        @Inject(BARCODE_SCANNER) private barcodeScanner: BarcodeScanner) {
    }

    ngOnInit() {
        console.log("ngOnInit AccountSelection");
        this.accounts = this.accountService.getRegisteredAccounts();
        this.isLoading = false;
    }

    ngOnChanges() {
        console.log("ngOnChanges");
    }

    hasAccounts(): boolean {
        return this.accounts.length > 0;
    }

    requestQrScan() {
        this.isLoading = true;
        this.barcodeScanner.scan(defaultScanOptions)
            .then((result) => {
                this.isLoading = true;
                let scanResult = JSON.parse(result.text);
                this.accountService.registerNewAccount(scanResult.baseUrl, scanResult.username, scanResult.password)
                    .subscribe(resp => this.processResponse(resp), () => {
                        alert("error")
                        this.isLoading = false;
                    });

            }, (error) => {
                console.log("No scan: " + error);
                this.isLoading = false;
            });
    }

    manage(account: Account): void {
        if(this.isEditRequested(account)) {
            this.editedAccount = null;
        } else {
            this.routerExtensions.navigate(['/manage-account/', account.getKey()]);
        }
        
    }

    onLongPress(account: Account): void {
        this.editedAccount = account;
    }

    isEditRequested(account: Account): boolean {
        return this.editedAccount === account;
    }

    delete(account: Account): void {
        let newAccounts = this.accountService.deleteAccount(account);
        this.accounts = newAccounts;
    }

    private processResponse(accountResponse: AccountResponse) {
        console.log("success!");
        if (!accountResponse.isExisting()) {
            console.log("pushing itemResult");
            this.accounts.push(accountResponse.getAccount());
            console.log("done. current list size: " + this.accounts.length);
        }
        this.isLoading = false;
    }

}